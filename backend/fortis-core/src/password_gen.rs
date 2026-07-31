use rand::seq::SliceRandom;
use rand::Rng;

pub struct PasswordGenerator {
    pub length: usize,
    pub include_uppercase: bool,
    pub include_numbers: bool,
    pub include_symbols: bool,
}

impl Default for PasswordGenerator {
    fn default() -> Self {
        Self {
            length: 16,
            include_uppercase: true,
            include_numbers: true,
            include_symbols: true,
        }
    }
}

impl PasswordGenerator {
    pub fn generate(&self) -> zeroize::Zeroizing<String> {
        let lowercase = b"abcdefghijklmnopqrstuvwxyz";
        let uppercase = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        let numbers = b"0123456789";
        let symbols = b"!@#$%^&*()_+-=[]{}|;:,.<>?";

        let mut charset = Vec::new();
        charset.extend_from_slice(lowercase);
        
        if self.include_uppercase {
            charset.extend_from_slice(uppercase);
        }
        if self.include_numbers {
            charset.extend_from_slice(numbers);
        }
        if self.include_symbols {
            charset.extend_from_slice(symbols);
        }

        let mut rng = rand::thread_rng();
        let password: String = (0..self.length)
            .map(|_| {
                let idx = rng.gen_range(0..charset.len());
                charset[idx] as char
            })
            .collect();
            
        zeroize::Zeroizing::new(password)
    }
}
