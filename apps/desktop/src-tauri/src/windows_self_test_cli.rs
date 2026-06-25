fn main() {
    let report = remote_desk_desktop_lib::run_windows_self_test();
    match serde_json::to_string_pretty(&report) {
        Ok(output) => println!("{output}"),
        Err(error) => {
            eprintln!("failed to serialize desktop self-test report: {error}");
            std::process::exit(2);
        }
    }

    if !report.ok {
        std::process::exit(1);
    }
}
