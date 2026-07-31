use clap::{Parser, Subcommand};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

const SOCKET_PATH: &str = "/tmp/fortis_agent.sock";

#[derive(Parser)]
#[command(name = "fortis")]
#[command(about = "CLI for FortisEncrypt Password Manager", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Add a new entry to the vault
    Add { title: String },
    /// Get an entry by its title
    Get { title: String },
    /// Generate a cryptographically secure password
    Generate {
        #[arg(short, long, default_value_t = 16)]
        length: usize,
    },
    /// Trigger an immediate peer-to-peer sync
    Sync,
    /// Pair this Linux machine with your mobile device
    Pair,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();

    let mut stream = match UnixStream::connect(SOCKET_PATH).await {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Failed to connect to agent. Is fortis-agent running?\nError: {}", e);
            std::process::exit(1);
        }
    };

    let command_str = match &cli.command {
        Commands::Add { title } => format!("ADD {}", title),
        Commands::Get { title } => format!("GET {}", title),
        Commands::Generate { length } => format!("GENERATE {}", length),
        Commands::Sync => "SYNC".to_string(),
        Commands::Pair => "PAIR_START".to_string(),
    };

    stream.write_all(command_str.as_bytes()).await?;

    // Close the write half so the daemon knows we're done sending
    stream.shutdown().await?;

    let mut response = String::new();
    stream.read_to_string(&mut response).await?;

    println!("{}", response);

    Ok(())
}
