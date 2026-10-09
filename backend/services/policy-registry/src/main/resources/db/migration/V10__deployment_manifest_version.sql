-- Record which Active Pointer Manifest a deployment actually took effect in.
--
-- §5.2 has `deployments` and `pointer_manifests` as separate tables with no link between them, and
-- for the data plane that is correct: a pod reads the manifest and nothing else, so the manifest is
-- self-contained by design.
--
-- The gap is on the operator's side. "This deployment is ACTIVE" and "the pods are serving it" are
-- different statements: between the two sits a manifest that has to be written, signed, published
-- and adopted (§8.2, §8.5). Without this column the only way to answer "did my change actually
-- reach the pods" is to read the latest manifest's JSON and look for the bundle hash — which is
-- what somebody does at 2am when a change appears not to have taken effect, and it is the wrong
-- thing to be doing at 2am.
--
-- With it, the question is a column: an ACTIVE deployment whose manifest_version is NULL never
-- published, and a pod reporting an older manifest version in `serving_reports` has not caught up
-- yet. Those are two different problems with two different fixes.

ALTER TABLE bre_ctl.deployments
  ADD COLUMN manifest_version bigint;

COMMENT ON COLUMN bre_ctl.deployments.manifest_version IS
  'The Active Pointer Manifest version this deployment was published in. NULL on an ACTIVE row means the activation did not publish — the deployment exists and no pod can see it. Compare against serving_reports to tell "not published" from "not yet adopted".';

-- Deliberately not a foreign key to pointer_manifests. That table is keyed by (cell_id,
-- manifest_version) and a deployment does not carry a cell: the cell is a property of the TENANT,
-- which can in principle be migrated between cells. A foreign key would therefore either need the
-- cell duplicated onto every deployment row — a second copy of a fact that can change — or it
-- would break on a tenant migration. The column is a cross-reference for an operator, not a
-- referential constraint.
