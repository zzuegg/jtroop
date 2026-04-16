plugins {
    java
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("bench.HttpServerMain")
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

tasks.register<JavaExec>("runHttp") {
    group = "application"
    description = "Run standalone HTTP server for wrk testing"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("bench.HttpServerMain")
    jvmArgs = listOf("--enable-preview")
}
