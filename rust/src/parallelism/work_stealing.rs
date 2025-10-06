use std::sync::Arc;
use crossbeam_queue::SegQueue;
use std::sync::atomic::{AtomicUsize, Ordering};

pub struct WorkStealingScheduler<T> {
    tasks: Arc<SegQueue<T>>,
    task_count: Arc<AtomicUsize>,
}

impl<T> WorkStealingScheduler<T> {
    pub fn new(tasks: Vec<T>) -> Self {
            let task_count = Arc::new(AtomicUsize::new(tasks.len()));
            let queue = Arc::new(SegQueue::new());
            
            for task in tasks {
                queue.push(task);
            }
            
            Self {
                tasks: queue,
                task_count,
            }
        }

    pub fn execute<F, R>(self, processor: F) -> Vec<R>
        where
            F: Fn(T) -> R + Send + Sync + 'static,
            T: Send + 'static,
            R: Send + 'static,
        {
            let processor = Arc::new(processor);
            let results: Arc<SegQueue<R>> = Arc::new(SegQueue::new());
            
            // Use rayon to process tasks in parallel
            let num_threads = rayon::current_num_threads();
            let handles: Vec<_> = (0..num_threads)
                .map(|_| {
                    let tasks = Arc::clone(&self.tasks);
                    let task_count = Arc::clone(&self.task_count);
                    let processor = Arc::clone(&processor);
                    let results = Arc::clone(&results);
                    
                    std::thread::spawn(move || {
                        let mut local_results = Vec::new();
                        
                        while task_count.load(Ordering::Relaxed) > 0 {
                            // Try to get a task without locking
                            match tasks.pop() {
                                Some(task) => {
                                    // Process the task
                                    let result = processor(task);
                                    local_results.push(result);
                                    
                                    // Decrement task count atomically
                                    task_count.fetch_sub(1, Ordering::Relaxed);
                                }
                                None => {
                                    // No more tasks available, exit loop
                                    break;
                                }
                            }
                        }
                        
                        // Add local results to shared results without locking
                        if !local_results.is_empty() {
                            for r in local_results {
                                results.push(r);
                            }
                        }
                    })
                })
                .collect();
            
            // Wait for all threads to complete
            for handle in handles {
                handle.join().expect("worker thread panicked");
            }
    
            // Extract results from queue into vector
            let mut out = Vec::new();
                while let Some(r) = results.pop() {
                out.push(r);
            }
            out
        }
}