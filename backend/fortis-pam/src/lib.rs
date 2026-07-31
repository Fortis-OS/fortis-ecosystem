use pamsm::{PamServiceModule, Pam, PamFlags, PamError};
use std::os::unix::net::UnixStream;
use std::io::{Write, Read};
use std::time::Duration;

struct FortisPam;

impl PamServiceModule for FortisPam {
    fn authenticate(
        _pamh: Pam,
        _flags: PamFlags,
        _args: Vec<String>,
    ) -> PamError {
        // We do not care about the arguments for now.
        // We will contact the local fortis-agent via Unix IPC.
        
        // Attempt to connect to the agent
        let socket_path = "/tmp/fortis_agent.sock";
        let mut stream = match UnixStream::connect(socket_path) {
            Ok(s) => s,
            Err(_) => return PamError::IGNORE, // Fall back to password
        };
        
        // Set a 10 second timeout for read/write
        let timeout = Some(Duration::from_secs(10));
        let _ = stream.set_read_timeout(timeout);
        let _ = stream.set_write_timeout(timeout);

        // Send AUTH_CHALLENGE command
        if stream.write_all(b"AUTH_CHALLENGE\n").is_err() {
            return PamError::IGNORE;
        }

        // Wait for response
        let mut response = String::new();
        let mut buf = [0; 128];
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break, // EOF
                Ok(n) => {
                    response.push_str(&String::from_utf8_lossy(&buf[..n]));
                    if response.contains("AUTH_SUCCESS") {
                        return PamError::SUCCESS;
                    } else if response.contains("AUTH_FAILURE") {
                        return PamError::IGNORE; // Let PAM ask for password
                    }
                }
                Err(_) => {
                    // Timeout or other read error
                    return PamError::IGNORE;
                }
            }
        }

        PamError::IGNORE
    }

    fn setcred(
        _pamh: Pam,
        _flags: PamFlags,
        _args: Vec<String>,
    ) -> PamError {
        PamError::SUCCESS
    }
}

pamsm::pam_module!(FortisPam);
