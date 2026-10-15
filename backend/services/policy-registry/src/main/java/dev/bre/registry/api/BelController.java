package dev.bre.registry.api;

import dev.bre.registry.auth.Role;
import dev.bre.registry.bel.BelLanguageService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The BEL language service, for the editor (TRD §13.3, U4 — M5-02).
 *
 * <p><b>One parser, one answer.</b> A BEL parser in the browser would be a second answer to "is
 * this expression valid", and the two would diverge first on the case nobody tested — so an author
 * would be told their expression is fine and the submit gate would refuse it, or worse, the
 * reverse. This is the same lexer, the same parser and the same type checker the compile path uses.
 *
 * <p><b>POLICY_READ, not POLICY_EDIT.</b> Checking an expression changes nothing and writes
 * nothing; a reviewer reading a rule should be able to hover a subexpression and see its type.
 */
@RestController
@RequestMapping("/v1/bel")
public class BelController {

  private final BelLanguageService language;

  /**
   * Creates the controller.
   *
   * @param language the language service
   */
  public BelController(BelLanguageService language) {
    this.language = language;
  }

  /**
   * What to check, and what the policy has in scope.
   *
   * @param expression the BEL source
   * @param parameters parameter name to declared type
   * @param referenceLists list name to declared type
   * @param variables variable name to declared type
   */
  public record CheckRequest(
      String expression,
      Map<String, String> parameters,
      Map<String, String> referenceLists,
      Map<String, String> variables) {}

  /**
   * Parses and type-checks an expression.
   *
   * @param request the request
   * @param body what to check
   * @return the inferred type and every positioned diagnostic
   */
  @PostMapping("/check")
  public BelLanguageService.Checked check(
      HttpServletRequest request, @RequestBody CheckRequest body) {
    Authorisation.require(request, Role.Capability.POLICY_READ);
    return language.check(
        body.expression(),
        body.parameters() == null ? Map.of() : body.parameters(),
        body.referenceLists() == null ? Map.of() : body.referenceLists(),
        body.variables() == null ? Map.of() : body.variables());
  }

  /**
   * Everything an author can reference, for completion.
   *
   * @param request the request
   * @param body what the policy has in scope
   * @return the offers
   */
  @PostMapping("/completions")
  public List<BelLanguageService.Completion> completions(
      HttpServletRequest request, @RequestBody CheckRequest body) {
    Authorisation.require(request, Role.Capability.POLICY_READ);
    return language.completions(
        body.parameters() == null ? Map.of() : body.parameters(),
        body.referenceLists() == null ? Map.of() : body.referenceLists(),
        body.variables() == null ? Map.of() : body.variables());
  }

  /**
   * The keywords and function names, for the editor's tokenizer.
   *
   * <p>Served rather than duplicated in the client, so a keyword added to the grammar highlights
   * without a UI release — and, more to the point, so the editor cannot highlight a keyword the
   * parser does not have.
   *
   * @param request the request
   * @return the grammar's keywords and function names
   */
  @GetMapping("/grammar")
  public Map<String, List<String>> grammar(HttpServletRequest request) {
    Authorisation.require(request, Role.Capability.POLICY_READ);
    return Map.of("keywords", language.keywords(), "functions", language.functionNames());
  }
}
