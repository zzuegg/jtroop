plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    implementation(project(":benchmark:common"))
    implementation("io.netty:netty-all:4.2.1.Final")
    jmh(project(":benchmark:common"))
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    jvmArgs.addAll(listOf("--enable-preview"))
    fork.set(1)
    warmupIterations.set(1)
    iterations.set(2)
    benchmarkMode.set(listOf("thrpt"))
    profilers.addAll(listOf("gc"))
    forceGC.set(true)
}
