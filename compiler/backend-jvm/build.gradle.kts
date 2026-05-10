plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":compiler:cbpv-core"))
    implementation("org.ow2.asm:asm:9.7.1")

    testImplementation(kotlin("test"))
    testImplementation("org.ow2.asm:asm-util:9.7.1")
    testImplementation("org.ow2.asm:asm-analysis:9.7.1")
}

tasks.test {
    useJUnitPlatform()
}
