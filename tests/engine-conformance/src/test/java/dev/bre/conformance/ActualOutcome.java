package dev.bre.conformance;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the engine actually produced for a vector, as JSON shaped like the vector's {@code expect}
 * object.
 *
 * <p>Comparing JSON to JSON rather than engine types to engine types is deliberate: it keeps the
 * suite language-neutral, so the same vectors can check a non-JVM engine, and it stops the
 * assertions from drifting with internal refactors.
 *
 * @param expect the outcome, in the same shape as a vector's {@code expect} field
 */
public record ActualOutcome(JsonNode expect) {}
