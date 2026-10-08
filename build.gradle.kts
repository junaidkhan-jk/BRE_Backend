// Root build. Module conventions live in build-logic/ (Build Plan §4).

tasks.register("checkLibsAreMavenCentralOnly") {
    group = "verification"
    description =
        "Asserts no libs/* project can resolve from any repository other " +
            "than Maven Central. This is the executable form of the D11 " +
            "deviation boundary: trustt-platform-lib is permitted in " +
            "services/* only."

    val offenders = provider {
        rootProject.subprojects
            .filter { it.path.startsWith(":libs:") }
            .flatMap { sub ->
                sub.repositories
                    .filterIsInstance<org.gradle.api.artifacts.repositories.UrlArtifactRepository>()
                    .map { sub.path to it }
            }
            .filterNot { (_, repo) ->
                repo.url.toString().startsWith("https://repo.maven.apache.org/maven2") ||
                    repo.url.toString().startsWith("https://repo1.maven.org/maven2")
            }
            .map { (path, repo) -> "$path -> ${repo.url}" }
    }

    doLast {
        val bad = offenders.get()
        require(bad.isEmpty()) {
            "libs/* must resolve from Maven Central only (D11). Offending " +
                "repositories:\n" + bad.joinToString("\n") { "  $it" }
        }
        logger.lifecycle("libs/* resolve from Maven Central only: ${rootProject.subprojects.count { it.path.startsWith(":libs:") }} projects checked.")
    }
}

tasks.register("check") {
    group = "verification"
    description = "Root aggregate: the repository boundary plus every module's own check."
    dependsOn(tasks.named("checkLibsAreMavenCentralOnly"))
    dependsOn(subprojects.map { "${it.path}:check" })
}
