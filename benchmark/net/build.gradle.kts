plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":benchmark:common"))
    jmh(project(":core"))
    jmh(project(":benchmark:common"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    jvmArgs.addAll(listOf("--enable-preview"))
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    benchmarkMode.set(listOf("thrpt", "avgt"))
    profilers.addAll(listOf("gc"))
}
