/**
 * Build entry point for the Rust native layer.
 */

/** Resolves the cargo executable: PATH first, then the default rustup location. */
fun cargoExecutable(): String {
    val inHome = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    return if (inHome.canExecute()) inHome.absolutePath else "cargo"
}

/** Compiles both host native libraries in release mode into `native/target/release/`. */
tasks.register<Exec>("buildHostNatives") {
    group = "native"
    description = "Builds the host Rust native libraries (release) into native/target/release for the client to bundle."
    val dir = projectDir
    val cargo = cargoExecutable()
    workingDir = dir
    commandLine(cargo, "build", "--release")
    doFirst { logger.lifecycle("Building host natives with '$cargo' in $dir...") }
}

/** Runs the Rust native test suite. */
tasks.register<Exec>("testHostNatives") {
    group = "native"
    description = "Runs the Rust native test suite (cargo test)."
    workingDir = projectDir
    commandLine(cargoExecutable(), "test")
}
