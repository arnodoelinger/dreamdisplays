fun cargoExecutable(): String {
    val inHome = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    return if (inHome.canExecute()) inHome.absolutePath else "cargo"
}

tasks.register<Exec>("buildHostNatives") {
    group = "native"
    description = "Builds the host Rust native libraries (release) into native/target/release for the client to bundle."
    val dir = projectDir
    val cargo = cargoExecutable()
    workingDir = dir
    environment.remove("DEVELOPER_DIR")
    commandLine(cargo, "build", "--release")
    doFirst { logger.lifecycle("Building host natives with '$cargo' in $dir...") }
}

tasks.register<Exec>("testHostNatives") {
    group = "native"
    description = "Runs the Rust native test suite (cargo test)."
    workingDir = projectDir
    environment.remove("DEVELOPER_DIR")
    commandLine(cargoExecutable(), "test")
}
