plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.blockscout.proxy.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    enabled = false
}

tasks.register<JavaExec>("integrationTest") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.blockscout.proxy.ProxyIntegrationTest"
}

tasks.check {
    dependsOn("integrationTest")
}

tasks.jar {
    manifest.attributes["Main-Class"] = application.mainClass.get()
}
