-- Keep the author's own PSF source alongside the translated IR.
--
-- §5.2 stores `ir` only, and for *serving* that is right: the IR is what gets signed into a bundle
-- and what the engine evaluates, so it is the artefact that has to be exact. But a registry is
-- also an editor, and an editor that cannot give an author back what they wrote is not usable.
--
-- The IR is not a substitute, because the translation is lossy in exactly the ways that matter to
-- a person:
--
--   * every comment is gone — and a credit policy's comments are where the reason for a threshold
--     lives, which is the single most valuable thing in the file to the next author;
--   * key order, blank lines and block style are normalised, so a reopened policy would come back
--     reformatted and every diff would be noise;
--   * YAML anchors and multi-line scalars are expanded, so a table written once and referenced
--     three times comes back as three copies.
--
-- Round-tripping IR → PSF would produce a file that compiles to the same decisions and that no
-- author would recognise as theirs. So both are stored: `psf_source` is what the author edits and
-- what a diff is taken over, `ir` is what is signed and evaluated, and `compile_reports` is the
-- evidence that the second was derived from the first.
--
-- Nullable, because a freshly created DRAFT has no source yet — it exists so the author has
-- somewhere to save to.

ALTER TABLE bre_ctl.policy_versions
  ADD COLUMN psf_source text;

COMMENT ON COLUMN bre_ctl.policy_versions.psf_source IS
  'The author''s PSF YAML, verbatim. The IR beside it is what gets signed and evaluated; this is what the author edits and what a diff is taken over, because translation discards every comment and normalises the formatting.';
