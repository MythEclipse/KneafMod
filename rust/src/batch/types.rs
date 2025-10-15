/// Minimal Batch related types used as stubs to satisfy cross-module imports

#[derive(Debug, Clone)]
pub enum BatchError {
    ParseError(String),
    ExecutionError(String),
}

impl std::fmt::Display for BatchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BatchError::ParseError(msg) => write!(f, "Parse error: {}", msg),
            BatchError::ExecutionError(msg) => write!(f, "Execution error: {}", msg),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum BatchOperationType {
    Echo = 0,
    Heavy = 1,
    PanicTest = 2,
}

impl TryFrom<u8> for BatchOperationType {
    type Error = String;

    fn try_from(v: u8) -> Result<Self, Self::Error> {
        match v {
            0 => Ok(BatchOperationType::Echo),
            1 => Ok(BatchOperationType::Heavy),
            2 => Ok(BatchOperationType::PanicTest),
            _ => Err(format!("Invalid BatchOperationType: {}", v)),
        }
    }
}
