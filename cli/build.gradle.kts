plugins {
    application
}

application {
    mainClass.set("dev.openclaude.cli.Main")
    applicationDefaultJvmArgs = listOf()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(project(":tools"))
    implementation(project(":engine"))
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}
