plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    implementation(project(":benchmark:common"))
    implementation("org.jmonkeyengine:jme3-networking:3.7.0-stable")
    implementation("org.jmonkeyengine:jme3-core:3.7.0-stable")
    jmh(project(":benchmark:common"))
    jmh("org.jmonkeyengine:jme3-networking:3.7.0-stable")
    jmh("org.jmonkeyengine:jme3-core:3.7.0-stable")
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
