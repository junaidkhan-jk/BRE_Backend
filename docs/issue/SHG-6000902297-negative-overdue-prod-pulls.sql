-- =====================================================================
-- Negative "Total Overdue" on SHG parent — PROD read-only pulls
--
-- Ticket   : Br coll — SHG-Reschedule Group reflecting negative value
--            in Total Overdue field
-- Group    : LOAN_SHG0604202600422036  (SEMBARATHI MAHALIR SHG)
-- Parent   : LAN 6000902297  /  loan_account_id 16088012
-- Children : 16092609 (CLOSED, death foreclosure), 16092610, 16092611,
--            16092612, 16092613, 16092614, 16092615, 16092616
-- Observed : Total Overdue = -97.00 in NP app Group 360 and in the
--            1+ Report (Current Day Total EMI Due), DPD = 23
-- Code base: mfi_integration_v3.6.1  (verified identical to v3.6.1.2.1
--            for every file in the read and write paths)
--
-- ALL STATEMENTS BELOW ARE READ-ONLY. No DML, no DDL.
-- Run PULL G (sweeps) off-peak or on a read replica — full scans.
--
-- Already established from the data already supplied, do not re-derive:
--   every reader computes  due_amount - paid_amount - waived_amount
--   with no floor. On 2026-10-08 the open rows are
--     2026-09-15 PRIN  21920 - 20983 -   0  = + 937
--     2026-09-15 INT    6778 -  6875 - 937  = -1034
--     2026-09-19 PRIN  86072 - 86072 -   0  =     0
--                                     TOTAL =   - 97   <-- matches both surfaces
--   The INT row is over-settled by 1034 (paid+waived 7812 vs due 6778).
--   loan_installment_details.settled_amount = 27761 = 20983 + 6778,
--   i.e. the instalment ledger recorded INT as 6778 — the two tables
--   disagree by 97. Correct overdue is +937, not -97 and not 0.
--
-- OPEN QUESTION these pulls exist to close:
--   which writer set paid_amount = 6875 and waived_amount = 937 on the
--   2026-09-15 INT row, and in which order.
-- =====================================================================


-- =====================================================================
-- PULL D  — MOST DECISIVE. Run this one first.
-- Parent vs children on the same dates.
--
-- Closes: the parent must equal the sum of its members. If
--   sum(children INT paid @ 2026-09-15) = 6875  -> the defect is
--     upstream in the child settlement and the parent only mirrored it
--   sum(...)                             = 6778  -> the parent write
--     itself is the bug
-- Either answer halves the remaining search space.
-- =====================================================================
select ldd.loan_account_id,
       la.la_account_number,
       ldd.component_type,
       ldd.due_date,
       ldd.due_amount,
       ldd.paid_amount,
       ldd.waived_amount,
       ldd.due_amount - ldd.paid_amount - ldd.waived_amount as outstanding,
       ldd.is_deleted,
       ldd.loan_installment_details_id,
       ldd.created_on, ldd.created_by,
       ldd.updated_on, ldd.updated_by
  from mfi_accounting.loan_due_details ldd
  join mfi_accounting.loan_account la on la.account_id = ldd.loan_account_id
 where ldd.loan_account_id in (16088012, 16092609, 16092610, 16092611,
                               16092612, 16092613, 16092614, 16092615, 16092616)
   and ldd.due_date in ('2026-09-15', '2026-09-19', '2026-10-15')
 order by ldd.due_date, ldd.loan_account_id, ldd.component_type;

-- Same thing rolled up, for the one-line answer:
select ldd.due_date,
       ldd.component_type,
       sum(case when ldd.loan_account_id = 16088012 then ldd.paid_amount   else 0 end) as parent_paid,
       sum(case when ldd.loan_account_id <> 16088012 then ldd.paid_amount  else 0 end) as children_paid,
       sum(case when ldd.loan_account_id = 16088012 then ldd.waived_amount else 0 end) as parent_waived,
       sum(case when ldd.loan_account_id <> 16088012 then ldd.waived_amount else 0 end) as children_waived
  from mfi_accounting.loan_due_details ldd
 where ldd.loan_account_id in (16088012, 16092609, 16092610, 16092611,
                               16092612, 16092613, 16092614, 16092615, 16092616)
   and ldd.due_date in ('2026-09-15', '2026-09-19')
   and ldd.is_deleted = false
 group by ldd.due_date, ldd.component_type
 order by ldd.due_date, ldd.component_type;


-- =====================================================================
-- PULL A — Waiver provenance.
--
-- Closes: who posted the 937, under which identifier_type
--   (DEATH_FORECLOSURE / prepayment / manual maker-checker), and
--   against which component it was recorded. The 937 equals exactly the
--   unpaid PRIN of instalment 5, yet it sits on the INT row.
--
-- NOTE: adjust the join column names if they differ in this schema
--       (waiver_loan_due_details.waiver_details_id / loan_due_details_id).
-- =====================================================================
select wd.*,
       wl.loan_due_details_id,
       ldd.component_type,
       ldd.due_date,
       ldd.due_amount,
       ldd.paid_amount,
       ldd.waived_amount
  from mfi_accounting.waiver_details wd
  join mfi_accounting.waiver_loan_due_details wl
    on wl.waiver_details_id = wd.id
  join mfi_accounting.loan_due_details ldd
    on ldd.id = wl.loan_due_details_id
 where ldd.loan_account_id in (16088012, 16092609)
 order by wd.created_on;

-- Fallback if the link table is keyed differently:
select * from mfi_accounting.waiver_details
 where loan_account_id in (16088012, 16092609) order by created_on;

select wl.*
  from mfi_accounting.waiver_loan_due_details wl
 where wl.loan_due_details_id in (
         select id from mfi_accounting.loan_due_details
          where loan_account_id in (16088012, 16092609));


-- =====================================================================
-- PULL B — Payment allocation.
--
-- Closes: whether the allocator subtracted waived_amount before writing
--   paid_amount. 1+ Report shows last payment 24-09-2026 / 24730 / CASH,
--   and the INT row alone carries updated_on 2026-09-24 12:07:27.014.
-- =====================================================================
select p.id,
       p.transaction_ref_number,
       p.value_date,
       p.created_on, p.created_by,
       p.principal_amount, p.interest_amount, p.penalty_amount,
       p.fee_amount, p.excess_amount,
       x.paid_amount as allocated_to_row,
       ldd.id        as loan_due_details_id,
       ldd.component_type,
       ldd.due_date,
       ldd.due_amount,
       ldd.paid_amount,
       ldd.waived_amount
  from mfi_accounting.loan_account_payments_details p
  left join mfi_accounting.loan_due_details_loan_account_payments_details x
         on x.loan_account_payments_details_id = p.id
  left join mfi_accounting.loan_due_details ldd
         on ldd.id = x.loan_due_details_id
 where p.loan_account_id in (16088012, 16092609)
 order by p.created_on, ldd.due_date, ldd.component_type;


-- =====================================================================
-- PULL C — Transaction legs, 2026-09-15 .. 2026-09-26.
--
-- Closes: names the workflow that ran (RSCH_DEATH_FORECLOSURE vs
--   DEATH_FORECLOSURE vs repayment) and yields the reference numbers
--   needed to pull the application logs.
-- =====================================================================
select tm.id,
       tm.reference_number,
       tm.value_date,
       tm.created_on,
       tm.original_amount,
       tm.reversed,
       tc.type, tc.sub_type,
       tp.entity_id,
       tp.reference_code,
       tp.cr_dr_indicator,
       tp.amount,
       tp.gl_code
  from mfi_accounting.transaction_master tm
  join mfi_accounting.transaction_partition_details tp on tp.transaction_id = tm.id
  join mfi_accounting.transaction_catalogue tc        on tc.id = tm.transaction_catalogue_id
 where tp.entity_id in (16088012, 16092609)
   and tm.value_date between '2026-09-15' and '2026-09-26'
 order by tm.created_on, tp.id;


-- =====================================================================
-- PULL E — Reschedule inputs.
--
-- Closes: what the 2026-09-19 10:11:12 run was TOLD to waive, versus
--   what landed in loan_due_details.
-- =====================================================================
select * from mfi_accounting.death_foreclosure_details
 where loan_account_id = 16092609 order by created_on;

select * from mfi_accounting.prepayment_details
 where loan_account_id in (16088012, 16092609) order by created_on;

select * from mfi_accounting.prepayment_charge_details
 where prepayment_details_id in (
         select id from mfi_accounting.prepayment_details
          where loan_account_id in (16088012, 16092609));

select * from mfi_accounting.loan_account_part_prepayment_details
 where loan_account_id in (16088012, 16092609) order by created_on;


-- =====================================================================
-- PULL F — Audit stamps on the two bad rows.
--
-- Closes: SYSTEM vs user id vs batch id as the writer. Also explains the
--   anomalous stamp on the PRIN row, updated_on = 2026-07-20 00:00:00,
--   which is BEFORE its own due date of 2026-09-15.
-- =====================================================================
select id,
       component_type,
       due_date,
       overdue_date,
       due_amount,
       paid_amount,
       waived_amount,
       loan_installment_details_id,
       is_deleted,
       created_on, created_by,
       updated_on, updated_by
  from mfi_accounting.loan_due_details
 where loan_account_id = 16088012
   and due_date in ('2026-09-15', '2026-09-19')
 order by due_date, component_type;

-- The instalment-ledger side of the 97 discrepancy:
select lid.id,
       lid.serial_number,
       lid.installment_date,
       lid.installment_amount,
       lid.settled_amount,
       lid.is_settled,
       lid.last_paid_date,
       lid.is_deleted,
       lid.updated_on, lid.updated_by
  from mfi_accounting.loan_installment_details lid
 where lid.loan_account_id = 16088012
 order by lid.id;


-- =====================================================================
-- PULL G — BLAST RADIUS. Run off-peak or on a read replica.
-- Turns this from one ticket into a sized defect.
-- =====================================================================

-- G1. Every over-settled row in the book: paid + waived > due.
select la.la_account_number,
       ldd.loan_account_id,
       count(*)                                                        as bad_rows,
       sum(ldd.paid_amount + ldd.waived_amount - ldd.due_amount)       as excess,
       min(ldd.due_date)                                               as first_bad_due_date,
       max(ldd.updated_on)                                             as last_touched
  from mfi_accounting.loan_due_details ldd
  join mfi_accounting.loan_account la on la.account_id = ldd.loan_account_id
 where ldd.is_deleted = false
   and ldd.paid_amount + ldd.waived_amount > ldd.due_amount
 group by 1, 2
 order by excess desc
 limit 500;

-- G2. Accounts currently reporting a NEGATIVE overdue — the exact
--     symptom in this ticket, across the whole portfolio.
select ldd.loan_account_id,
       la.la_account_number,
       sum(ldd.due_amount - ldd.paid_amount - ldd.waived_amount)                  as reported_overdue,
       sum(greatest(ldd.due_amount - ldd.paid_amount - ldd.waived_amount, 0))     as correct_overdue
  from mfi_accounting.loan_due_details ldd
  join mfi_accounting.loan_account la on la.account_id = ldd.loan_account_id
 where ldd.is_deleted = false
   and ldd.due_date <= current_date
 group by 1, 2
having sum(ldd.due_amount - ldd.paid_amount - ldd.waived_amount) < 0
 order by reported_overdue asc
 limit 500;

-- G3. Instalment ledger vs due ledger disagreement — the "97" class.
select lid.loan_account_id,
       la.la_account_number,
       lid.id                                           as installment_id,
       lid.installment_date,
       lid.settled_amount,
       sum(ldd.paid_amount + ldd.waived_amount)         as ldd_sum,
       sum(ldd.paid_amount + ldd.waived_amount) - lid.settled_amount as delta
  from mfi_accounting.loan_installment_details lid
  join mfi_accounting.loan_due_details ldd
    on ldd.loan_installment_details_id = lid.id
   and ldd.is_deleted = false
  join mfi_accounting.loan_account la on la.account_id = lid.loan_account_id
 where lid.is_deleted = false
 group by 1, 2, 3, 4, 5
having sum(ldd.paid_amount + ldd.waived_amount) <> lid.settled_amount
 order by abs(sum(ldd.paid_amount + ldd.waived_amount) - lid.settled_amount) desc
 limit 500;

-- G4. SHG groups that have had a member death foreclosure — the
--     population at risk of this exact sequence.
select distinct parent.account_id        as parent_loan_account_id,
       parent.la_account_number          as parent_lan,
       child.account_id                  as deceased_child_id,
       dfd.date_of_death,
       dfd.status,
       dfd.created_on
  from mfi_accounting.death_foreclosure_details dfd
  join mfi_accounting.loan_account child  on child.account_id = dfd.loan_account_id
  join mfi_accounting.loan_account parent on parent.account_id = child.parent_loan_account_id
 order by dfd.created_on desc
 limit 500;

-- G5. Third inconsistent copy: loan_account.overdue_amount vs the
--     live sum. On 16088012 the column reads 0.000000 while the live
--     sum reads -97.
select la.account_id,
       la.la_account_number,
       la.past_due_days,
       la.overdue_amount                                             as column_value,
       coalesce(sum(ldd.due_amount - ldd.paid_amount - ldd.waived_amount), 0) as live_sum,
       ladf.total_due_overdue_amt                                    as derived_field
  from mfi_accounting.loan_account la
  left join mfi_accounting.loan_due_details ldd
         on ldd.loan_account_id = la.account_id
        and ldd.is_deleted = false
        and ldd.due_date <= current_date
  left join mfi_accounting.loan_account_derived_fields ladf
         on ladf.acc_id = la.account_id
 where la.account_id in (16088012, 16092609, 16092610, 16092611,
                         16092612, 16092613, 16092614, 16092615, 16092616)
 group by la.account_id, la.la_account_number, la.past_due_days,
          la.overdue_amount, ladf.total_due_overdue_amt;


-- =====================================================================
-- PULL H — The three downstream surfaces, to confirm the operational
-- impact is real and not only cosmetic.
-- =====================================================================

-- H1. The cached collections row the Br coll webapp reads.
select c.id,
       c.loan_account_number,
       c.due_date,
       c.product_type,
       c.group_loan,
       c.emi_due_amount,
       c.total_overdue_amount,
       c.lpp,
       c.fee_charges_due,
       c.pending_due_amount,
       c.collected_amount,
       c.emi_due_amount + c.total_overdue_amount + c.lpp + c.fee_charges_due
                                                   as collectible_as_computed_by_ui,
       c.updated_on
  from mfi_collections.collections c
 where c.loan_account_number in ('6000902297','6000902946','6000902942')
 order by c.due_date desc
 limit 50;

-- H2. The derived field the 1+ Report and every base_* view read.
select acc_id,
       total_due_overdue_amt,
       total_overdue_amt,
       updated_on
  from mfi_accounting.loan_account_derived_fields
 where acc_id = 16088012;

-- H3. Accounts that fall through every Br coll bucket. The list filters
--     are  total_overdue_amount > 0  /  = 0  /  = 0 , so a negative
--     value matches NONE of them and the account disappears from the
--     OVERDUE, DUE and UPCOMING worklists alike.
select count(*) as invisible_accounts
  from mfi_collections.collections
 where total_overdue_amount < 0;


-- =====================================================================
-- CONTEXT ALREADY HELD — reproduced here so the DBA can sanity-check
-- their result set against it. No need to re-run.
--
--   loan_account 16088012 : past_due_days 23, overdue_amount 0.000000,
--                           loan_status ACTIVE, has_child_accounts t
--   2026-09-15 PRIN       : due 21920, paid 20983, waived 0,
--                           updated_on 2026-07-20 00:00:00   <-- backdated
--   2026-09-15 INT        : due  6778, paid  6875, waived 937,
--                           updated_on 2026-09-24 12:07:27.014
--   2026-09-19 PRIN       : due 86072, paid 86072, waived 0,
--                           updated_on 2026-09-19 10:11:12.272
--   instalment 5          : amount 28698, settled 27761, is_settled f
--   instalments 6..30     : old rows at 28698 logically deleted,
--                           recreated at 24770 on 2026-09-19 10:11:12.272
--   child 16092609        : CLOSED, death_foreclosure_details
--                           updated_on 2026-09-17 and 2026-09-19
-- =====================================================================
