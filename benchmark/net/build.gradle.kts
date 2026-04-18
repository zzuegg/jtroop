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
    warmupIterations.set(5)
    warmup.set("3s")
    iterations.set(5)
    timeOnIteration.set("5s")
    benchmarkMode.set(listOf("thrpt"))
    val includeProp = (project.findProperty("jmh.include") as String?) ?: "Net(Game|Udp)Benchmark"
    includes.set(listOf(includeProp))
    (project.findProperty("jmh.timeOnIteration") as String?)?.let { timeOnIteration.set(it) }
    (project.findProperty("jmh.warmup") as String?)?.let { warmup.set(it) }
    profilers.addAll(listOf("gc", "stack"))
    forceGC.set(true)
    // Emit JSON so a committed golden baseline can be diffed by CI.
    resultFormat.set("JSON")
    resultsFile.set(file("build/results/jmh/results.json"))
}
