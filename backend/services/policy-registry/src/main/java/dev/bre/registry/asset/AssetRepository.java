package dev.bre.registry.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The shared-asset tables (§5.2, M3-03).
 *
 * <p><b>The schema was already here.</b> V2 created {@code asset_versions} with its review table,
 * the effective-dated {@code parameter_values} with a PostgreSQL exclusion constraint over a {@code
 * daterange}, the REGULATORY columns with their {@code source_ref}/{@code next_review_on} CHECK,
 * reference lists, reason codes, and authority matrices with ordered levels and amount-banded
 * entries. None of it had a service over it, so the bundle builder read a hand-edited {@code
 * assets-<tenant>.json} off disk instead. This is the service.
 *
 * <p><b>Four constraints are the database's, not this class's</b>, and that is deliberate — each
 * one holds for every writer including {@code psql}:
 *
 * <ul>
 *   <li>Two effective windows for the same parameter cannot overlap. The exclusion constraint
 *       refuses it; this class does not check it, so there is one implementation and not two.
 *   <li>A REGULATORY parameter must carry a source reference and a review date.
 *   <li>An authority entry's amount bands cannot overlap within a {@code (deviationType,
 *       productLine, segment)}.
 *   <li>A reason code cannot use a reserved prefix — {@code SYS_}, {@code DATA_MISSING}, {@code
 *       DEV_NO_AUTHORITY} — because the engine generates those itself (CHK-016).
 * </ul>
 */
@Repository
public class AssetRepository {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** §2.2 and FR-MODEL-008: a bundle carries at most this many items in one reference list. */
  public static final int MAX_LIST_ITEMS = 100_000;

  private final JdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the control-plane database
   */
  public AssetRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The four kinds of shared asset a policy can pin. */
  public enum Kind {
    /** Typed, named constants with effective-dated values. */
    PARAMETER_SET,
    /** A named set of values a policy tests membership against. */
    REFERENCE_LIST,
    /** Reason codes with internal and customer-facing text. */
    REASON_CODE_CATALOGUE,
    /** Deviation type and amount band to authority level. */
    AUTHORITY_MATRIX
  }

  /**
   * One asset version's header.
   *
   * @param kind which kind of asset
   * @param code its code
   * @param version its version number
   * @param status DRAFT, IN_REVIEW, APPROVED, ARCHIVED or DISCARDED
   * @param contentHash set once approved, over the canonical content
   * @param author who created the draft — the person a reviewer must not be
   * @param submissionRound how many times it has been submitted
   * @param approvedAt when it was approved
   * @param rowVersion for optimistic locking
   */
  public record Header(
      Kind kind,
      String code,
      int version,
      String status,
      Optional<String> contentHash,
      String author,
      int submissionRound,
      Optional<String> approvedAt,
      long rowVersion) {}

  /**
   * Finds one version.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version
   * @return the header, if it exists
   */
  public Optional<Header> find(String tenantId, Kind kind, String code, int version) {
    return jdbc
        .query(
            """
            SELECT asset_kind, asset_code, version, status, content_hash, author,
                   submission_round,
                   to_char(approved_at, 'YYYY-MM-DD"T"HH24:MI:SSZ') AS approved_at, row_version
              FROM bre_ctl.asset_versions
             WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND version = ?
            """,
            (rs, rowNum) -> header(rs),
            tenantId,
            kind.name(),
            code,
            version)
        .stream()
        .findFirst();
  }

  /**
   * Every version of every asset of a kind, newest first.
   *
   * @param tenantId the tenant
   * @param kind the kind, or null for all kinds
   * @return the headers
   */
  public List<Header> list(String tenantId, Kind kind) {
    return jdbc.query(
        """
        SELECT asset_kind, asset_code, version, status, content_hash, author, submission_round,
               to_char(approved_at, 'YYYY-MM-DD"T"HH24:MI:SSZ') AS approved_at, row_version
          FROM bre_ctl.asset_versions
         WHERE tenant_id = ? AND (?::text IS NULL OR asset_kind = ?::text)
         ORDER BY asset_kind, asset_code, version DESC
        """,
        (rs, rowNum) -> header(rs),
        tenantId,
        kind == null ? null : kind.name(),
        kind == null ? null : kind.name());
  }

  private static Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Header(
        Kind.valueOf(rs.getString("asset_kind")),
        rs.getString("asset_code"),
        rs.getInt("version"),
        rs.getString("status"),
        Optional.ofNullable(rs.getString("content_hash")),
        rs.getString("author"),
        rs.getInt("submission_round"),
        Optional.ofNullable(rs.getString("approved_at")),
        rs.getLong("row_version"));
  }

  /**
   * The highest APPROVED version of an asset.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @return the version number, if any version is approved
   */
  public Optional<Integer> latestApproved(String tenantId, Kind kind, String code) {
    return jdbc
        .query(
            """
            SELECT max(version) FROM bre_ctl.asset_versions
             WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND status = 'APPROVED'
            """,
            (rs, rowNum) -> (Integer) rs.getObject(1),
            tenantId,
            kind.name(),
            code)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /** The version an open DRAFT or IN_REVIEW occupies, if there is one. */
  public Optional<Integer> openVersion(String tenantId, Kind kind, String code) {
    return jdbc
        .query(
            """
            SELECT version FROM bre_ctl.asset_versions
             WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ?
               AND status IN ('DRAFT','IN_REVIEW')
             ORDER BY version DESC
            """,
            (rs, rowNum) -> rs.getInt(1),
            tenantId,
            kind.name(),
            code)
        .stream()
        .findFirst();
  }

  /** The next version number for an asset. */
  public int nextVersion(String tenantId, Kind kind, String code) {
    Integer highest =
        jdbc.queryForObject(
            """
            SELECT coalesce(max(version), 0) FROM bre_ctl.asset_versions
             WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ?
            """,
            Integer.class,
            tenantId,
            kind.name(),
            code);
    return (highest == null ? 0 : highest) + 1;
  }

  /**
   * Inserts an empty DRAFT.
   *
   * @param tenantId the tenant
   * @param kind the kind
   * @param code the asset code
   * @param version the version number
   * @param author who is creating it
   */
  public void insertDraft(String tenantId, Kind kind, String code, int version, String author) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version, status,
                                            author)
        VALUES (?, ?, ?, ?, 'DRAFT', ?)
        """,
        tenantId,
        kind.name(),
        code,
        version,
        author);
  }

  /** Moves a DRAFT to IN_REVIEW. */
  public void submit(String tenantId, Kind kind, String code, int version) {
    jdbc.update(
        """
        UPDATE bre_ctl.asset_versions
           SET status = 'IN_REVIEW', submission_round = submission_round + 1,
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND version = ?
           AND status = 'DRAFT'
        """,
        tenantId,
        kind.name(),
        code,
        version);
  }

  /** Returns an IN_REVIEW version to DRAFT. */
  public void returnToDraft(String tenantId, Kind kind, String code, int version) {
    jdbc.update(
        """
        UPDATE bre_ctl.asset_versions
           SET status = 'DRAFT', row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND version = ?
           AND status = 'IN_REVIEW'
        """,
        tenantId,
        kind.name(),
        code,
        version);
  }

  /**
   * Approves a version, stamping the content hash.
   *
   * <p>The hash is over the canonical content, with the same BRE-JCS canonicalisation a bundle hash
   * uses — so "did this parameter set change" is answerable by comparing two strings, and a
   * reordered insert does not look like a change.
   */
  public void approve(String tenantId, Kind kind, String code, int version, String contentHash) {
    jdbc.update(
        """
        UPDATE bre_ctl.asset_versions
           SET status = 'APPROVED', approved_at = now(), content_hash = ?,
               row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND version = ?
           AND status = 'IN_REVIEW'
        """,
        contentHash,
        tenantId,
        kind.name(),
        code,
        version);
  }

  /** Marks a DRAFT discarded. A version is never deleted. */
  public void discard(String tenantId, Kind kind, String code, int version) {
    jdbc.update(
        """
        UPDATE bre_ctl.asset_versions
           SET status = 'DISCARDED', row_version = row_version + 1, updated_at = now()
         WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND version = ? AND status = 'DRAFT'
        """,
        tenantId,
        kind.name(),
        code,
        version);
  }

  /** Records a review decision. */
  public void recordReview(
      String tenantId,
      Kind kind,
      String code,
      int version,
      int round,
      String reviewer,
      boolean approve,
      String comment) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.asset_version_reviews (tenant_id, asset_kind, asset_code,
            asset_version, submission_round, reviewer, decision, comment)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, asset_kind, asset_code, asset_version, submission_round, reviewer)
        DO UPDATE SET decision = excluded.decision, comment = excluded.comment
        """,
        tenantId,
        kind.name(),
        code,
        version,
        round,
        reviewer,
        approve ? "APPROVE" : "REQUEST_CHANGES",
        comment);
  }

  /** How many distinct reviewers approved this submission round. */
  public int approvalCount(String tenantId, Kind kind, String code, int version, int round) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM bre_ctl.asset_version_reviews
             WHERE tenant_id = ? AND asset_kind = ? AND asset_code = ? AND asset_version = ?
               AND submission_round = ? AND decision = 'APPROVE'
            """,
            Integer.class,
            tenantId,
            kind.name(),
            code,
            version,
            round);
    return count == null ? 0 : count;
  }

  // ------------------------------------------------------------------ parameter sets

  /** Replaces a parameter set's whole content. Only legal while the version is a DRAFT. */
  public void replaceParameters(String tenantId, String code, int version, JsonNode parameters) {
    jdbc.update(
        "DELETE FROM bre_ctl.parameter_values WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);
    jdbc.update(
        "DELETE FROM bre_ctl.parameters WHERE tenant_id = ? AND asset_code = ? AND asset_version = ?",
        tenantId,
        code,
        version);

    for (JsonNode parameter : parameters) {
      String name = parameter.path("name").asText();
      jdbc.update(
          """
          INSERT INTO bre_ctl.parameters (tenant_id, asset_code, asset_version, name, value_type,
              regulatory, source_ref, next_review_on, description)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?::date, ?)
          """,
          tenantId,
          code,
          version,
          name,
          parameter.path("type").asText(),
          parameter.path("regulatory").asBoolean(false),
          text(parameter, "sourceRef"),
          text(parameter, "nextReviewOn"),
          text(parameter, "description"));

      for (JsonNode window : parameter.path("values")) {
        jdbc.update(
            """
            INSERT INTO bre_ctl.parameter_values (tenant_id, asset_code, asset_version, name,
                effective_from, effective_to, value)
            VALUES (?, ?, ?, ?, ?::date, ?::date, ?)
            """,
            tenantId,
            code,
            version,
            name,
            window.path("effectiveFrom").asText(),
            text(window, "effectiveTo"),
            window.path("value").asText());
      }
    }
  }

  /**
   * A parameter set's content, in the shape §8.4 gives it in a bundle.
   *
   * <p>Built as the bundle's own format rather than some intermediate of its own, so one codec
   * reads it whether it came from the database or from a file. A second shape here would be a
   * second place the effective-window semantics could drift.
   *
   * @param tenantId the tenant
   * @param code the asset code
   * @param version the version
   * @return {@code {code, version, parameters:[{name,type,regulatory,…,values:[…]}]}}
   */
  public ObjectNode parameterSet(String tenantId, String code, int version) {
    ObjectNode out = JSON.createObjectNode();
    out.put("code", code).put("version", version);
    ArrayNode parameters = out.putArray("parameters");

    for (Object[] row :
        jdbc.query(
            """
            SELECT name, value_type, regulatory, source_ref,
                   to_char(next_review_on, 'YYYY-MM-DD') AS next_review_on, description
              FROM bre_ctl.parameters
             WHERE tenant_id = ? AND asset_code = ? AND asset_version = ?
             ORDER BY name
            """,
            (rs, rowNum) ->
                new Object[] {
                  rs.getString("name"),
                  rs.getString("value_type"),
                  rs.getBoolean("regulatory"),
                  rs.getString("source_ref"),
                  rs.getString("next_review_on"),
                  rs.getString("description")
                },
            tenantId,
            code,
            version)) {
      ObjectNode parameter = parameters.addObject();
      parameter.put("name", (String) row[0]).put("type", (String) row[1]);
      parameter.put("regulatory", (Boolean) row[2]);
      if (row[3] != null) {
        parameter.put("sourceRef", (String) row[3]);
      }
      if (row[4] != null) {
        parameter.put("nextReviewOn", (String) row[4]);
      }
      if (row[5] != null) {
        parameter.put("description", (String) row[5]);
      }
      ArrayNode windows = parameter.putArray("values");
      jdbc.query(
          """
          SELECT to_char(effective_from, 'YYYY-MM-DD') AS effective_from,
                 to_char(effective_to,   'YYYY-MM-DD') AS effective_to, value
            FROM bre_ctl.parameter_values
           WHERE tenant_id = ? AND asset_code = ? AND asset_version = ? AND name = ?
           ORDER BY effective_from
          """,
          rs -> {
            ObjectNode window = windows.addObject();
            window.put("effectiveFrom", rs.getString("effective_from"));
            String to = rs.getString("effective_to");
            if (to == null) {
              window.putNull("effectiveTo");
            } else {
              window.put("effectiveTo", to);
            }
            window.put("value", rs.getString("value"));
          },
          tenantId,
          code,
          version,
          row[0]);
      parameters.set(parameters.size() - 1, parameter);
    }
    return out;
  }

  // ------------------------------------------------------------------ reference lists

  /**
   * Replaces a reference list's whole content.
   *
   * @throws AssetService.AssetException when the list is larger than a bundle may carry
   */
  public void replaceList(
      String tenantId,
      String code,
      int version,
      String itemType,
      String description,
      List<String> items) {
    if (items.size() > MAX_LIST_ITEMS) {
      // FR-MODEL-008. The cap is on what a BUNDLE carries, because a bundle is loaded into every
      // pod's memory and verified on every cold start. A list that big is a dataset, and the
      // answer is to send the membership check as a fact instead.
      throw new AssetService.AssetException(
          "a reference list may hold at most "
              + MAX_LIST_ITEMS
              + " items and this one has "
              + items.size()
              + ". A bundle is verified and held in memory by every decision pod, so a list this"
              + " large belongs outside it: compute the membership check in your own system and"
              + " send the result as a fact.");
    }
    jdbc.update(
        "DELETE FROM bre_ctl.reference_list_items WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);
    jdbc.update(
        "DELETE FROM bre_ctl.reference_list_meta WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);
    jdbc.update(
        """
        INSERT INTO bre_ctl.reference_list_meta (tenant_id, asset_code, asset_version, item_type,
            description)
        VALUES (?, ?, ?, ?, ?)
        """,
        tenantId,
        code,
        version,
        itemType,
        description);
    // Batched: a 100,000-item list inserted one statement at a time is a minute of round trips.
    List<Object[]> batch = new ArrayList<>(items.size());
    for (String item : items.stream().distinct().toList()) {
      batch.add(new Object[] {tenantId, code, version, item});
    }
    jdbc.batchUpdate(
        """
        INSERT INTO bre_ctl.reference_list_items (tenant_id, asset_code, asset_version, item)
        VALUES (?, ?, ?, ?)
        """,
        batch);
  }

  /** A reference list's content, in the shape §8.4 gives it in a bundle. */
  public ObjectNode referenceList(String tenantId, String code, int version) {
    ObjectNode out = JSON.createObjectNode();
    out.put("code", code).put("version", version);
    jdbc.query(
        """
        SELECT item_type, description FROM bre_ctl.reference_list_meta
         WHERE tenant_id = ? AND asset_code = ? AND asset_version = ?
        """,
        rs -> {
          out.put("itemType", rs.getString("item_type"));
          if (rs.getString("description") != null) {
            out.put("description", rs.getString("description"));
          }
        },
        tenantId,
        code,
        version);
    ArrayNode items = out.putArray("items");
    jdbc.query(
        """
        SELECT item FROM bre_ctl.reference_list_items
         WHERE tenant_id = ? AND asset_code = ? AND asset_version = ? ORDER BY item
        """,
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> items.add(rs.getString("item")),
        tenantId,
        code,
        version);
    return out;
  }

  // ------------------------------------------------------------------ reason codes

  /** Replaces a reason-code catalogue's whole content. */
  public void replaceReasonCodes(String tenantId, String code, int version, JsonNode codes) {
    jdbc.update(
        "DELETE FROM bre_ctl.reason_codes WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);
    for (JsonNode reason : codes) {
      jdbc.update(
          """
          INSERT INTO bre_ctl.reason_codes (tenant_id, asset_code, asset_version, code, category,
              adverse, internal_text, customer_text)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
          """,
          tenantId,
          code,
          version,
          reason.path("code").asText(),
          reason.path("category").asText(),
          reason.path("adverse").asBoolean(false),
          reason.path("internalText").asText(),
          reason.path("customerText").toString());
    }
  }

  /** A reason-code catalogue's content. */
  public ObjectNode reasonCodes(String tenantId, String code, int version) {
    ObjectNode out = JSON.createObjectNode();
    out.put("code", code).put("version", version);
    ArrayNode codes = out.putArray("codes");
    jdbc.query(
        """
        SELECT code, category, adverse, internal_text, customer_text::text AS customer_text
          FROM bre_ctl.reason_codes
         WHERE tenant_id = ? AND asset_code = ? AND asset_version = ? ORDER BY code
        """,
        rs -> {
          ObjectNode reason = codes.addObject();
          reason.put("code", rs.getString("code"));
          reason.put("category", rs.getString("category"));
          reason.put("adverse", rs.getBoolean("adverse"));
          reason.put("internalText", rs.getString("internal_text"));
          reason.set("customerText", readJson(rs.getString("customer_text")));
        },
        tenantId,
        code,
        version);
    return out;
  }

  // ------------------------------------------------------------------ authority matrices

  /** Replaces an authority matrix's whole content: its routing mode, levels and entries. */
  public void replaceAuthorityMatrix(
      String tenantId,
      String code,
      int version,
      String routingMode,
      JsonNode levels,
      JsonNode entries) {
    jdbc.update(
        "DELETE FROM bre_ctl.authority_entries WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);
    jdbc.update(
        "DELETE FROM bre_ctl.authority_levels WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);
    jdbc.update(
        "DELETE FROM bre_ctl.authority_matrix_meta WHERE tenant_id = ? AND asset_code = ?"
            + " AND asset_version = ?",
        tenantId,
        code,
        version);

    jdbc.update(
        """
        INSERT INTO bre_ctl.authority_matrix_meta (tenant_id, asset_code, asset_version,
            routing_mode)
        VALUES (?, ?, ?, ?)
        """,
        tenantId,
        code,
        version,
        routingMode);

    for (JsonNode level : levels) {
      jdbc.update(
          """
          INSERT INTO bre_ctl.authority_levels (tenant_id, asset_code, asset_version, level_code,
              rank)
          VALUES (?, ?, ?, ?, ?)
          """,
          tenantId,
          code,
          version,
          level.path("levelCode").asText(),
          level.path("rank").asInt());
    }

    int entryNo = 0;
    for (JsonNode entry : entries) {
      entryNo++;
      jdbc.update(
          """
          INSERT INTO bre_ctl.authority_entries (tenant_id, asset_code, asset_version, entry_no,
              deviation_type, product_line, segment, amount_above, amount_up_to, level_code)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?)
          """,
          tenantId,
          code,
          version,
          entryNo,
          entry.path("deviationType").asText(),
          entry.path("productLine").asText("*"),
          entry.path("segment").asText("*"),
          text(entry, "amountAbove"),
          text(entry, "amountUpTo"),
          entry.path("levelCode").asText());
    }
  }

  /** An authority matrix's content, in the shape the IR's {@code authorityMatrix} takes. */
  public ObjectNode authorityMatrix(String tenantId, String code, int version) {
    ObjectNode out = JSON.createObjectNode();
    out.put("code", code).put("version", version);
    jdbc.query(
        """
        SELECT routing_mode FROM bre_ctl.authority_matrix_meta
         WHERE tenant_id = ? AND asset_code = ? AND asset_version = ?
        """,
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> out.put("routingMode", rs.getString("routing_mode")),
        tenantId,
        code,
        version);

    ArrayNode levels = out.putArray("levels");
    jdbc.query(
        """
        SELECT level_code, rank FROM bre_ctl.authority_levels
         WHERE tenant_id = ? AND asset_code = ? AND asset_version = ? ORDER BY rank
        """,
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                levels
                    .addObject()
                    .put("levelCode", rs.getString("level_code"))
                    .put("rank", rs.getInt("rank")),
        tenantId,
        code,
        version);

    ArrayNode entries = out.putArray("entries");
    jdbc.query(
        """
        SELECT deviation_type, product_line, segment, amount_above, amount_up_to, level_code
          FROM bre_ctl.authority_entries
         WHERE tenant_id = ? AND asset_code = ? AND asset_version = ? ORDER BY entry_no
        """,
        rs -> {
          ObjectNode entry = entries.addObject();
          entry.put("deviationType", rs.getString("deviation_type"));
          entry.put("productLine", rs.getString("product_line"));
          entry.put("segment", rs.getString("segment"));
          if (rs.getBigDecimal("amount_above") != null) {
            entry.put("amountAbove", rs.getBigDecimal("amount_above").toPlainString());
          }
          if (rs.getBigDecimal("amount_up_to") != null) {
            entry.put("amountUpTo", rs.getBigDecimal("amount_up_to").toPlainString());
          }
          entry.put("levelCode", rs.getString("level_code"));
        },
        tenantId,
        code,
        version);
    return out;
  }

  // ------------------------------------------------------------------ pins

  /**
   * Pins the asset versions a policy version resolved against (FR-MODEL-003).
   *
   * <p>Recorded at approval, so "which parameter set was this policy signed with" is answerable
   * from the control plane and not only by opening the bundle. A later change to the asset
   * publishes a new asset version and leaves this row — and the signed bundle — untouched.
   */
  public void pin(
      String tenantId, String policyCode, int policyVersion, Kind kind, String code, int version) {
    jdbc.update(
        """
        INSERT INTO bre_ctl.policy_version_pins (tenant_id, policy_code, policy_version,
            asset_kind, asset_code, asset_version)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, policy_code, policy_version, asset_kind, asset_code)
        DO UPDATE SET asset_version = excluded.asset_version
        """,
        tenantId,
        policyCode,
        policyVersion,
        kind.name(),
        code,
        version);
  }

  /** What a policy version pinned. */
  public List<String> pinsOf(String tenantId, String policyCode, int policyVersion) {
    return jdbc.query(
        """
        SELECT asset_kind || ' ' || asset_code || '@' || asset_version
          FROM bre_ctl.policy_version_pins
         WHERE tenant_id = ? AND policy_code = ? AND policy_version = ?
         ORDER BY asset_kind, asset_code
        """,
        (rs, rowNum) -> rs.getString(1),
        tenantId,
        policyCode,
        policyVersion);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static JsonNode readJson(String json) {
    try {
      return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("a jsonb column is not valid JSON", e);
    }
  }
}
