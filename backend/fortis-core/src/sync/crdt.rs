use automerge::AutoCommit;

pub struct SyncEngine {
    pub doc: AutoCommit,
}

impl SyncEngine {
    pub fn new() -> Self {
        Self {
            doc: AutoCommit::new(),
        }
    }

    pub fn save(&mut self) -> Vec<u8> {
        self.doc.save()
    }

    pub fn load(data: &[u8]) -> Result<Self, automerge::AutomergeError> {
        let doc = AutoCommit::load(data)?;
        Ok(Self { doc })
    }

    pub fn merge(&mut self, other: &mut Self) -> Result<(), automerge::AutomergeError> {
        self.doc.merge(&mut other.doc)?;
        Ok(())
    }
}
