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
    fixed_content = fixed_content.replace(", A: 'a>", ">");
    fixed_content = fixed_content.replace(", A: 'a", "");

    // Fix E0053: method `follow` has an incompatible type for trait (add unsafe)
    // This was causing `unsafe unsafe fn`, so we'll replace the double unsafe.
    fixed_content = fixed_content.replace("unsafe unsafe fn follow", "unsafe fn follow");
    fixed_content = fixed_content.replace("fn follow(buf: &'a [u8], loc: usize) -> Self::Inner {", "unsafe fn follow(buf: &'a [u8], loc: usize) -> Self::Inner {");


    // Fix E0133: call to unsafe function `flatbuffers::Table::<'a>::get` is unsafe and requires unsafe function or block
    // This is a bit tricky as we need to wrap the call in an unsafe block.
    // We'll look for lines containing `self._tab.get` and wrap them.
    let lines: Vec<&str> = fixed_content.lines().collect();
    let mut new_lines: Vec<String> = Vec::new();
    for line in lines {
            if line.contains("self._tab.get") && !line.trim_start().starts_with("unsafe {") {
                let trimmed_line = line.trim_start();
                let indent = line.len() - trimmed_line.len();
                // Wrap the call in an unsafe block while preserving indentation
                new_lines.push(format!("{}unsafe {{ {} }}", " ".repeat(indent), trimmed_line));
        } else {
            new_lines.push(line.to_string());
        }
    }
    fixed_content = new_lines.join("\n");

    // Fix E0451: fields `buf` and `loc` of struct `flatbuffers::Table` are private
    fixed_content = fixed_content.replace(
        "Self { _tab: flatbuffers::Table { buf, loc } }",
        "Self { _tab: flatbuffers::Table::new(buf, loc) }"
    );
    // Collapse any accidental duplicated `unsafe` tokens introduced by earlier replacements
    fixed_content = fixed_content.replace("unsafe unsafe fn follow", "unsafe fn follow");
    fixed_content = fixed_content.replace("unsafe unsafe ", "unsafe ");
    
    std::fs::write(file_path, fixed_content)
        .expect(&format!("Failed to write fixed content to {}", file_path));
}