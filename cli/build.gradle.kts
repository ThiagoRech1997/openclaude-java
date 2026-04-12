plugins {
    application
}

application {
    mainClass.set("dev.openclaude.cli.Main")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(project(":tools"))
    implementation(project(":engine"))
    implementation(project(":mcp"))
    implementation(project(":commands"))
    implementation(project(":plugins"))
    implementation(project(":grpc"))
    implementation(project(":tui"))
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
}
