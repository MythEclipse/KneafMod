pub mod conversions;

// Re-export the FlatBuffers generated types
pub mod entity {
    // Stub types for FlatBuffers compatibility
    pub struct EntityData<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct PlayerData<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct Config<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct EntityInput<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct EntityProcessResult<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
}

pub mod item {
    pub struct ItemEntityData<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct ItemInput<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct ItemProcessResult<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
}

pub mod mob {
    pub struct MobData<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct MobInput<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct MobProcessResult<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
}

pub mod block {
    pub struct BlockEntityData<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct BlockInput<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
    
    pub struct BlockProcessResult<'a> {
        pub _phantom: std::marker::PhantomData<&'a ()>,
    }
}

// Re-export flatbuffers types