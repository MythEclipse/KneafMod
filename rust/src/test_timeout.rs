/// Global test watchdog to ensure CI/test runs don't hang indefinitely.
/// Configure via env RUST_TEST_GLOBAL_TIMEOUT_SECS (seconds). Default: 300s.
#[cfg(test)]
#[ctor::ctor]
fn start_test_watchdog() {
    std::thread::spawn(|| {
        let default = 300u64;
        let secs = std::env::var("RUST_TEST_GLOBAL_TIMEOUT_SECS")
            .ok()
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(default);

        std::thread::sleep(std::time::Duration::from_secs(secs));

        eprintln!("Global test watchdog: timeout of {}s reached, aborting.", secs);
        // Abort the process so test run is terminated.
        std::process::exit(124);
    });
}
