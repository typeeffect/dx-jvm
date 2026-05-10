plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":compiler:cbpv-core"))
    implementation(project(":compiler:frontend"))
    implementation(project(":compiler:backend-jvm"))

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dx.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
