fn main() {
    // Generate FlatBuffers code from schemas
    println!("cargo:rerun-if-changed=schemas/entity.fbs");
    println!("cargo:rerun-if-changed=schemas/item.fbs");
    println!("cargo:rerun-if-changed=schemas/mob.fbs");
    println!("cargo:rerun-if-changed=schemas/block.fbs");

    // Use flatc to generate Rust code
    std::process::Command::new("flatc")
        .args(&["--rust", "-o", "src/flatbuffers/entity/", "schemas/entity.fbs"])
        .status()
        .expect("Failed to generate FlatBuffers code for entity.fbs");

    // Fix the generated FlatBuffers code for compatibility with flatbuffers 23.5.26
    fix_flatbuffers_compatibility("src/flatbuffers/entity/entity_generated.rs");
    
    std::process::Command::new("flatc")
        .args(&["--rust", "-o", "src/flatbuffers/item/", "schemas/item.fbs"])
        .status()
        .expect("Failed to generate FlatBuffers code for item.fbs");

    fix_flatbuffers_compatibility("src/flatbuffers/item/item_generated.rs");
    
    std::process::Command::new("flatc")
        .args(&["--rust", "-o", "src/flatbuffers/mob/", "schemas/mob.fbs"])
        .status()
        .expect("Failed to generate FlatBuffers code for mob.fbs");

    fix_flatbuffers_compatibility("src/flatbuffers/mob/mob_generated.rs");
    
    std::process::Command::new("flatc")
        .args(&["--rust", "-o", "src/flatbuffers/block/", "schemas/block.fbs"])
        .status()
        .expect("Failed to generate FlatBuffers code for block.fbs");

    fix_flatbuffers_compatibility("src/flatbuffers/block/block_generated.rs");
}

fn fix_flatbuffers_compatibility(file_path: &str) {
    let content = std::fs::read_to_string(file_path)
        .expect(&format!("Failed to read {}", file_path));
    
    let mut fixed_content = content.clone();
    
    // Remove extern crate flatbuffers;
    fixed_content = fixed_content.replace("extern crate flatbuffers;", "");
    
    // Fix self::flatbuffers imports
    fixed_content = fixed_content.replace("use self::flatbuffers", "use flatbuffers");
    
    // Fix FlatBufferBuilder generic parameters (remove the generic parameter)
    fixed_content = fixed_content.replace("FlatBufferBuilder<'a, A>", "FlatBufferBuilder<'a>");
    fixed_content = fixed_content.replace("FlatBufferBuilder<'bldr, A>", "FlatBufferBuilder<'bldr>");
    fixed_content = fixed_content.replace("FlatBufferBuilder<'b, A>", "FlatBufferBuilder<'b>");
    
    // Fix flatbuffers::Allocator references (remove them)
    fixed_content = fixed_content.replace("flatbuffers::Allocator + ", "");
    fixed_content = fixed_content.replace("A: flatbuffers::Allocator", "A: ");
    
    // Clean up any remaining generic parameter issues
    fixed_content = fixed_content.replace(", A>", ">");
    fixed_content = fixed_content.replace("<'a, 'b, A>", "<'a, 'b>");
    fixed_content = fixed_content.replace("<'a, 'b, A,", "<'a, 'b,");
    
    std::fs::write(file_path, fixed_content)
        .expect(&format!("Failed to write fixed content to {}", file_path));
}