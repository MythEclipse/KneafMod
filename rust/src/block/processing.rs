use super::types::*;
use rayon::prelude::*;

pub fn process_block_entities(input: BlockInput) -> BlockProcessResult {
    // Always tick all block entities to prevent functional issues
    // Use parallel processing for better performance with large numbers
    let block_entities_to_tick: Vec<u64> = input.block_entities.par_iter().map(|be| be.id).collect();
    BlockProcessResult { block_entities_to_tick }
}