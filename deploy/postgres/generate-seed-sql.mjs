#!/usr/bin/env node
/*
 * Regenerates deploy/postgres/02-seed-data.sql from src/main/resources/seed-data.json.
 *
 *     node deploy/postgres/generate-seed-sql.mjs
 *
 * It reproduces exactly what DataSeeder does when it fills an empty database:
 * the same agreement numbers, the same even split of the expenses booked on
 * several units, the same price history derived from the rents and the same
 * fraction -> percentage conversion. Rerun it whenever seed-data.json changes.
 *
 * The tax filings are NOT generated: their snapshot is the JSON of a report
 * computed by TaxService from the seeded figures, so DataSeeder registers them
 * on the first start against this database (its incremental path is idempotent
 * and leaves everything else untouched).
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..');
const seed = JSON.parse(readFileSync(join(repo, 'src/main/resources/seed-data.json'), 'utf8'));

// ---------------------------------------------------------------- helpers

/** SQL literal: NULL, a quoted string, a number or a boolean. */
const lit = (v) => {
  if (v === null || v === undefined) return 'NULL';
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  if (typeof v === 'number') return String(v);
  return `'${String(v).replace(/'/g, "''")}'`;
};

const row = (values) => `(${values.map(lit).join(', ')})`;

/** Amounts carry at most two decimals; cents keep the arithmetic exact. */
const cents = (amount) => Math.round(Number(amount) * 100);
const euros = (c) => (c / 100).toFixed(2);

/** DataSeeder.parseShare: "1/6" -> 16.6667, a plain number is a percentage. */
const parseShare = (raw) => {
  const text = String(raw).trim();
  const slash = text.indexOf('/');
  if (slash > 0) {
    const numerator = Number(text.slice(0, slash).trim());
    const denominator = Number(text.slice(slash + 1).trim());
    return (Math.round((numerator * 100 * 10000) / denominator) / 10000).toFixed(4);
  }
  return Number(text.replace('%', '').trim()).toFixed(4);
};

const insert = (table, columns, rows) =>
  rows.length === 0
    ? ''
    : `INSERT INTO ${table} (${columns.join(', ')}) VALUES\n${rows.join(',\n')};\n`;

const out = [];
const restarts = [];
const emit = (title, table, columns, rows, nextId) => {
  out.push(`-- ${title} (${rows.length})`);
  out.push(insert(table, columns, rows));
  restarts.push(`ALTER TABLE ${table} ALTER COLUMN id RESTART WITH ${nextId};`);
};

// ---------------------------------------------------------------- clients

const clientIds = new Map();
const clientRows = seed.clients.map((c, i) => {
  clientIds.set(c.fullName, i + 1);
  return row([i + 1, c.fullName, c.email, c.phone, c.documentId ?? null, c.notes ?? null]);
});
emit('Tenants', 'clients',
  ['id', 'full_name', 'email', 'phone', 'document_id', 'notes'],
  clientRows, clientRows.length + 1);

// ---------------------------------------------------------------- units
// Two passes, as DataSeeder does: the top-level units first, so a parent always
// exists by the time the units inside it are created.

const unitIds = new Map();
const unitRows = [];
const unitOrder = [];
for (const pass of [0, 1]) {
  for (const u of seed.units) {
    const hasParent = u.parent !== undefined && u.parent !== null;
    if (hasParent !== (pass === 1)) continue;
    const id = unitRows.length + 1;
    unitIds.set(u.unitNumber, id);
    unitOrder.push(u.unitNumber);
    unitRows.push(row([
      id, u.unitNumber, u.kind ?? 'STORAGE_UNIT', hasParent ? unitIds.get(u.parent) : null,
      u.name, Number(u.sizeSquareMeters), u.location ?? null, u.cadastralReference ?? null,
      Number(u.baseMonthlyRate), u.status, u.description ?? null,
    ]));
  }
}
emit('Units: the two locales, the 9 trasteros inside "BD" and the 4 apartments', 'storage_units',
  ['id', 'unit_number', 'kind', 'parent_unit_id', 'name', 'size_square_meters', 'location',
    'cadastral_reference', 'base_monthly_rate', 'status', 'description'],
  unitRows, unitRows.length + 1);

// ---------------------------------------------------------------- rentals

const rentalsByRef = new Map();
const rentalRows = [];
let seq = 0;
for (const r of seed.rentals) {
  const unitId = unitIds.get(r.unit);
  const clientId = clientIds.get(r.client);
  if (unitId === undefined || clientId === undefined) {
    throw new Error(`rental ref ${r.ref}: unknown unit '${r.unit}' or client '${r.client}'`);
  }
  const id = rentalRows.length + 1;
  const active = r.endDate === undefined || r.endDate === null;
  const agreementNumber = `RNT-${r.startDate.slice(0, 4)}-${String(++seq).padStart(3, '0')}`;
  rentalsByRef.set(r.ref, { id, unitId, clientId, unit: r.unit, monthlyRent: r.monthlyRent, startDate: r.startDate });
  rentalRows.push(row([
    id, agreementNumber, unitId, clientId,
    r.coClient ? clientIds.get(r.coClient) : null,
    r.startDate, active ? null : r.endDate, 1,
    Number(r.monthlyRent), r.securityDeposit === undefined ? null : Number(r.securityDeposit),
    r.depositPaid === true, r.status, active, r.notes ?? null,
  ]));
}
emit('Tenancies', 'rental_agreements',
  ['id', 'agreement_number', 'storage_unit_id', 'client_id', 'co_client_id', 'start_date', 'end_date',
    'billing_day_of_month', 'monthly_rent', 'security_deposit', 'deposit_paid', 'status', 'auto_renew', 'notes'],
  rentalRows, rentalRows.length + 1);

// ---------------------------------------------------------------- payments
// Every seeded payment comes from a bank statement: PAID by transfer.

const paymentRows = [];
for (const p of seed.payments) {
  const rental = rentalsByRef.get(p.rentalRef);
  if (!rental) throw new Error(`payment of unknown rental ref ${p.rentalRef}`);
  paymentRows.push(row([
    paymentRows.length + 1, rental.id, rental.unitId, rental.clientId,
    p.month, p.year, Number(p.amount), Number(p.amountPaid ?? p.amount),
    p.dueDate, p.paymentDate, 'PAID', 'BANK_TRANSFER', p.notes ?? null,
  ]));
}
emit('Payments collected, from the bank statements', 'payments',
  ['id', 'rental_agreement_id', 'storage_unit_id', 'client_id', 'billing_period_month', 'billing_period_year',
    'amount_due', 'amount_paid', 'due_date', 'payment_date', 'status', 'payment_method', 'notes'],
  paymentRows, paymentRows.length + 1);

// ------------------------------------------------------- unit price history
// One entry per change of rent, in chronological order of the tenancies of the unit.

const historyRows = [];
for (const unitNumber of unitOrder) {
  const agreements = [...rentalsByRef.values()]
    .filter((r) => r.unit === unitNumber)
    .sort((a, b) => (a.startDate < b.startDate ? -1 : a.startDate > b.startDate ? 1 : 0));
  let previous = null;
  for (const r of agreements) {
    if (previous === null || cents(previous) !== cents(r.monthlyRent)) {
      historyRows.push(row([
        historyRows.length + 1, r.unitId, Number(r.monthlyRent), r.startDate,
        previous === null ? 'Precio inicial' : 'Cambio de precio',
      ]));
      previous = r.monthlyRent;
    }
  }
}
emit('Price history, derived from how the rent of each unit evolved', 'unit_price_history',
  ['id', 'storage_unit_id', 'monthly_price', 'effective_from', 'notes'],
  historyRows, historyRows.length + 1);

// ---------------------------------------------------------------- expenses
// An entry names one unit, several units among which the amount is split evenly
// (the rounding remainder going to the last one), or none: a general expense.

const expenseRows = [];
for (const e of seed.expenses) {
  const targets = Array.isArray(e.units) ? e.units.map(String) : e.unit !== undefined ? [String(e.unit)] : [];
  if (targets.length === 0) {
    expenseRows.push(row([expenseRows.length + 1, null, e.date, Number(e.amount), e.description, e.category]));
    continue;
  }
  const total = cents(e.amount);
  const each = Math.round(total / targets.length);
  let assigned = 0;
  targets.forEach((number, i) => {
    const unitId = unitIds.get(number);
    if (unitId === undefined) console.warn(`warning: unknown unit '${number}' for expense '${e.description}'`);
    const last = i === targets.length - 1;
    const share = targets.length === 1 ? total : last ? total - assigned : each;
    assigned += share;
    const text = targets.length === 1
      ? e.description
      : `${e.description} (1/${targets.length}, reparto ${targets.join('/')})`;
    expenseRows.push(row([
      expenseRows.length + 1, unitId ?? null, e.date, Number(euros(share)),
      text.slice(0, 255), e.category,
    ]));
  });
}
emit('Costs, from the outgoing side of the bank statements', 'expenses',
  ['id', 'storage_unit_id', 'expense_date', 'amount', 'description', 'category'],
  expenseRows, expenseRows.length + 1);

// ---------------------------------------------------------------- owners

const ownerIds = new Map();
const ownerRows = seed.owners.map((o, i) => {
  ownerIds.set(o.fullName, i + 1);
  return row([
    i + 1, o.fullName, o.type ?? 'PERSON', o.documentId ?? null, o.email ?? null,
    o.phone ?? null, o.bankAccount ?? null, o.notes ?? null,
  ]);
});
emit('Owners: the persons and the comunidad de bienes', 'owners',
  ['id', 'full_name', 'type', 'document_id', 'email', 'phone', 'bank_account', 'notes'],
  ownerRows, ownerRows.length + 1);

const membershipRows = [];
for (const o of seed.owners) {
  if (o.type !== 'COMUNIDAD_DE_BIENES' || !Array.isArray(o.members)) continue;
  for (const m of o.members) {
    membershipRows.push(row([
      membershipRows.length + 1, ownerIds.get(o.fullName), ownerIds.get(m.owner),
      Number(parseShare(m.share)), m.notes ?? null,
    ]));
  }
}
emit('Members of the comunidad de bienes and their share of its income', 'owner_memberships',
  ['id', 'entity_id', 'member_id', 'share_percent', 'notes'],
  membershipRows, membershipRows.length + 1);

const ownershipRows = seed.ownerships.map((s, i) => row([
  i + 1, ownerIds.get(s.owner), unitIds.get(s.unit), Number(parseShare(s.share)), s.notes ?? null,
]));
emit('Shares each owner holds in a unit', 'ownerships',
  ['id', 'owner_id', 'storage_unit_id', 'share_percent', 'notes'],
  ownershipRows, ownershipRows.length + 1);

// ---------------------------------------------------------------- file

const header = `-- =====================================================================
-- BoxVault - initial data
-- =====================================================================
--
-- GENERATED FILE - do not edit by hand.
--     node deploy/postgres/generate-seed-sql.mjs
-- regenerates it from src/main/resources/seed-data.json, which stays the single
-- source of truth (DataSeeder still loads it when running on H2).
--
-- The real history reconstructed from the BBVA / ING statements: the two locales
-- of Pasaxe 29 with the 9 trasteros inside "Bajo delantero", the 4 apartments,
-- their tenants, tenancies, collected rents, price history, costs and the
-- ownership shares. Identity counters are moved past the seeded ids at the end.
--
-- Not included: the tax_filings rows. Their snapshot is the JSON of a report
-- TaxService computes from these figures, so the application registers them
-- itself on the first start (DataSeeder's incremental path, which is idempotent
-- and creates nothing else once the tables below are filled).
-- =====================================================================

BEGIN;

`;

const footer = `
-- Identity columns continue after the seeded rows.
${restarts.join('\n')}

COMMIT;
`;

const target = join(here, '02-seed-data.sql');
writeFileSync(target, header + out.join('\n') + footer, 'utf8');

console.log(`Wrote ${target}`);
console.log(`  clients ${clientRows.length}, units ${unitRows.length}, rentals ${rentalRows.length}, ` +
  `payments ${paymentRows.length}, price history ${historyRows.length}, expenses ${expenseRows.length}, ` +
  `owners ${ownerRows.length}, memberships ${membershipRows.length}, ownerships ${ownershipRows.length}`);
