#[derive(Debug, Clone)]
pub struct SessionState {
    pub connected: bool,
    pub session_id: Option<String>,
}

impl SessionState {
    pub fn new() -> Self {
        Self {
            connected: false,
            session_id: None,
        }
    }
}
