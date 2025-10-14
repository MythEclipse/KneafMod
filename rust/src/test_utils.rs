
/// Run closure `f` in a new thread and wait for completion up to `dur`.
/// If the timeout elapses, this function panics to fail the test.
#[cfg(test)]
pub fn run_with_timeout<F>(dur: Duration, f: F)
where
    F: FnOnce() + Send + 'static,
{
    // Channel will carry Result<(), Box<dyn Any + Send>> where Err means the closure panicked
    let (tx, rx) = std::sync::mpsc::sync_channel(0);

    std::thread::spawn(move || {
        // Run closure under catch_unwind so we can transmit panic info
        let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| f()));
        // Ignore send errors
        let _ = tx.send(res);
    });

    match rx.recv_timeout(dur) {
        Ok(Ok(_)) => (),
        Ok(Err(panic_payload)) => std::panic::resume_unwind(panic_payload),
        Err(_) => panic!("test timed out after {:?}", dur),
    }
}

/// Macro wrapper for convenience in tests: run body with a timeout Duration.
#[macro_export]
macro_rules! run_test_timeout {
    ($dur:expr, $body:block) => {{
        $crate::test_utils::run_with_timeout($dur, || $body)
    }};
}
