plugins {
    java
    jacoco
    pmd
    id("org.springframework.boot") version "4.1.0"
}

group = "com.deskbooks"
version = "0.1.0"

val pmdVersion = "7.16.0"
val ckVersion = "0.7.0"

val pmdCli by configurations.creating
val ckCli by configurations.creating

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    runtimeOnly("org.xerial:sqlite-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    pmdCli("net.sourceforge.pmd:pmd-dist:$pmdVersion")
    ckCli("com.github.mauricioaniche:ck:$ckVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

jacoco {
    toolVersion = "0.8.14"
}

pmd {
    isConsoleOutput = true
    isIgnoreFailures = true
    toolVersion = pmdVersion
    ruleSets = listOf("category/java/design.xml", "category/java/errorprone.xml")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<org.gradle.api.plugins.quality.Pmd>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JavaExec>("cpdMain") {
    group = "verification"
    description = "Runs PMD CPD duplicate-code detection against main Java sources."
    classpath = pmdCli
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    isIgnoreExitValue = true

    val reportFile = layout.buildDirectory.file("reports/cpd/main.xml")
    doFirst {
        reportFile.get().asFile.parentFile.mkdirs()
    }
    args(
            "cpd",
            "--minimum-tokens",
            "75",
            "--language",
            "java",
            "--dir",
            file("src/main/java").absolutePath,
            "--format",
            "xml",
            "--no-fail-on-violation",
            "--report-file",
            reportFile.get().asFile.absolutePath)
}

tasks.register<JavaExec>("ckMain") {
    group = "verification"
    description = "Runs CK class and method metrics against main Java sources."
    classpath = ckCli
    mainClass.set("com.github.mauricioaniche.ck.Runner")

    val outputDir = layout.buildDirectory.dir("reports/ck")
    doFirst {
        outputDir.get().asFile.mkdirs()
    }
    args(
            file("src/main/java").absolutePath,
            "false",
            "0",
            "false",
            outputDir.get().asFile.absolutePath + "/",
            file("build").absolutePath)
}

tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("automationImport") {
    group = "application"
    description = "Previews or applies staged automated imports with the Java backend."
    mainClass.set("com.deskbooks.backend.DeskBooksApplication")
    classpath = sourceSets.main.get().runtimeClasspath
    args("--spring.main.web-application-type=none", "--deskbooks.command=automation-import")
    environment("PFA_SEED_STARTER_DATA", "0")
}

tasks.register("javaMetrics") {
    group = "verification"
    description = "Runs PMD, CPD, JaCoCo, CK, and prints a combined hotspot summary."
    dependsOn("pmdMain", "cpdMain", "jacocoTestReport", "ckMain")
    finalizedBy("javaMetricsSummary")
}

tasks.register<Exec>("javaMetricsSummary") {
    group = "verification"
    description = "Summarizes generated Java metric reports."
    commandLine("node", "../scripts/java-metrics-summary.mjs")
}
