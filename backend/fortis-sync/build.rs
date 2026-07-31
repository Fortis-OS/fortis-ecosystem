fn main() -> Result<(), Box<dyn std::error::Error>> {
    let protoc_bin = protoc_bin_vendored::protoc_bin_path().unwrap();
    unsafe {
        std::env::set_var("PROTOC", protoc_bin);
    }
    tonic_build::compile_protos("proto/sync.proto")?;
    Ok(())
}
