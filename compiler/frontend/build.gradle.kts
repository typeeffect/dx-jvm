plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":compiler:cbpv-core"))

    testImplementation(kotlin("test"))
    testImplementation(project(":compiler:backend-jvm"))
}

tasks.test {
    useJUnitPlatform()
}
