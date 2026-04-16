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
    warmupIterations.set(1)
    iterations.set(2)
    benchmarkMode.set(listOf("thrpt"))
    val includeProp = (project.findProperty("jmh.include") as String?) ?: "Net(Game|Udp)Benchmark"
    includes.set(listOf(includeProp))
    profilers.addAll(listOf("gc"))
    forceGC.set(true)
}
