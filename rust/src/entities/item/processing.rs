use crate::entities::item::types::{ItemInput, ItemProcessResult, ItemStack, ItemData};
use crate::errors::Result;

/// Process items for performance optimization
pub fn process_items(input: ItemInput) -> Result<ItemProcessResult> {
    let mut items_to_despawn = Vec::new();
    let mut items_to_merge = Vec::new();
    
    // Simple processing logic - despawn items that are too far from players
    for item_stack in &input.items {
        let min_player_distance = input.players.iter()
            .map(|player| {
                let dx = player.x - item_stack.item.properties.get("x").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0);
                let dy = player.y - item_stack.item.properties.get("y").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0);
                let dz = player.z - item_stack.item.properties.get("z").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0);
                (dx * dx + dy * dy + dz * dz).sqrt()
            })
            .min_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .unwrap_or(f64::INFINITY);
        
        // Despawn items that are too far away (more than 128 blocks)
        if min_player_distance > 128.0 {
            items_to_despawn.push(item_stack.item.id);
        }
    }
    
    // Group nearby items for merging
    let mut processed_items = Vec::new();
    for item in &input.items {
        if !items_to_despawn.contains(&item.item.id) {
            processed_items.push(item);
        }
    }
    
    // Simple merge logic - group items of the same type within 2 blocks
    let merge_distance = 2.0;
    let mut merge_groups: Vec<Vec<&ItemStack>> = Vec::new();
    
    for item in processed_items {
        let mut found_group = false;
        
        for group in &mut merge_groups {
            let group_center_x = group.iter()
                .map(|i| i.item.properties.get("x").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0))
                .sum::<f64>() / group.len() as f64;
            let group_center_y = group.iter()
                .map(|i| i.item.properties.get("y").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0))
                .sum::<f64>() / group.len() as f64;
            let group_center_z = group.iter()
                .map(|i| i.item.properties.get("z").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0))
                .sum::<f64>() / group.len() as f64;
            
            let item_x = item.item.properties.get("x").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0);
            let item_y = item.item.properties.get("y").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0);
            let item_z = item.item.properties.get("z").unwrap_or(&"0.0".to_string()).parse::<f64>().unwrap_or(0.0);
            
            let distance = ((item_x - group_center_x).powi(2) + (item_y - group_center_y).powi(2) + (item_z - group_center_z).powi(2)).sqrt();
            
            if distance <= merge_distance {
                group.push(item);
                found_group = true;
                break;
            }
        }
        
        if !found_group {
            merge_groups.push(vec![item]);
        }
    }
    
    // Create merge groups
    for (idx, group) in merge_groups.iter().enumerate() {
        if group.len() > 1 {
            let group_id = idx as u32;
            let item_ids = group.iter().map(|i| i.item.id).collect();
            
            let center_x = group.iter()
                .map(|i| i.item.properties.get("x").unwrap_or(&"0.0".to_string()).parse::<f32>().unwrap_or(0.0))
                .sum::<f32>() / group.len() as f32;
            let center_y = group.iter()
                .map(|i| i.item.properties.get("y").unwrap_or(&"0.0".to_string()).parse::<f32>().unwrap_or(0.0))
                .sum::<f32>() / group.len() as f32;
            let center_z = group.iter()
                .map(|i| i.item.properties.get("z").unwrap_or(&"0.0".to_string()).parse::<f32>().unwrap_or(0.0))
                .sum::<f32>() / group.len() as f32;
            
            items_to_merge.push(crate::entities::item::types::ItemMergeGroup {
                group_id,
                item_ids,
                merge_position: (center_x, center_y, center_z),
            });
        }
    }
    
    Ok(ItemProcessResult {
        items_to_despawn,
        items_to_merge,
    })
}

/// Process items in JSON format for JNI compatibility
pub fn process_items_json(input_json: &str) -> Result<String> {
    let input: ItemInput = serde_json::from_str(input_json)
        .map_err(|e| crate::errors::RustError::ValidationError(format!("Failed to parse item input JSON: {}", e)))?;
    
    let result = process_items(input)?;
    serde_json::to_string(&result)
        .map_err(|e| crate::errors::RustError::ValidationError(format!("Failed to serialize item result: {}", e)))
}