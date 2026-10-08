plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugins that the convention plugins below apply.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.3")
    implementation("org.cyclonedx:cyclonedx-gradle-plugin:1.4.0")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.1")
    // Needed on the classpath so bre.spring-service can `plugins { id(...) }`
    // it, even though no module applies that convention until M2.
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.1")
}
