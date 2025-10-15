use std::sync::Arc;

pub struct ScopedArena {
    // Basic arena implementation for demonstration
    pub name: String,
}

impl ScopedArena {
    pub fn new(name: &str) -> Self {
        ScopedArena {
            name: name.to_string(),
        }
    }
}

pub fn get_global_arena_pool() -> Arc<ScopedArena> {
    Arc::new(ScopedArena::new("global"))
}
