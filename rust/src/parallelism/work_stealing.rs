use rayon::prelude::*;
use std::sync::{Arc, Mutex};
use std::collections::VecDeque;

pub struct WorkStealingScheduler<T> {
    tasks: Arc<Mutex<VecDeque<T>>>,
}

impl<T> WorkStealingScheduler<T> {
    pub fn new(tasks: Vec<T>) -> Self {
        Self {
            tasks: Arc::new(Mutex::new(VecDeque::from(tasks))),
        }
    }

    pub fn execute<F, R>(self, processor: F) -> Vec<R>
    where
        F: Fn(T) -> R + Send + Sync + 'static,
        T: Send + 'static,
        R: Send + 'static,
    {
        let processor = Arc::new(processor);
        let results = Arc::new(Mutex::new(Vec::new()));
        
        // Use rayon to process tasks in parallel
        let num_threads = rayon::current_num_threads();
        let handles: Vec<_> = (0..num_threads)
            .map(|_| {
                let tasks = Arc::clone(&self.tasks);
                let processor = Arc::clone(&processor);
                let results = Arc::clone(&results);
                
                std::thread::spawn(move || {
                    let mut local_results = Vec::new();
                    
                    loop {
                        // Try to get a task
                        let task = {
                            let mut tasks_guard = tasks.lock().unwrap();
                            tasks_guard.pop_front()
                        };
                        
                        match task {
                            Some(task) => {
                                // Process the task
                                let result = processor(task);
                                local_results.push(result);
                            }
                            None => {
                                // No more tasks, exit
                                break;
                            }
                        }
                    }
                    
                    // Add local results to shared results
                    if !local_results.is_empty() {
                        let mut results_guard = results.lock().unwrap();
                        results_guard.extend(local_results);
                    }
                })
            })
            .collect();
        
        // Wait for all threads to complete
        for handle in handles {
            handle.join().expect("worker thread panicked");
        }

        // Extract results by locking the mutex and moving the vector out
        let final_results = Arc::clone(&results);
        let mut locked = final_results.lock().unwrap();
        let mut out = Vec::new();
        std::mem::swap(&mut out, &mut *locked);
        out
    }
}