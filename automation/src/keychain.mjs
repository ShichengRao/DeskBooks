import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export async function readGenericPassword({ service, account }) {
  if (!service) {
    throw new Error("keychain service is required");
  }
  if (!account) {
    throw new Error("keychain account is required");
  }
  try {
    const { stdout } = await execFileAsync("security", [
      "find-generic-password",
      "-s",
      service,
      "-a",
      account,
      "-w",
    ]);
    const password = stdout.trimEnd();
    if (!password) {
      throw new Error("keychain returned an empty password");
    }
    return password;
  } catch (error) {
    throw new Error(`could not read macOS Keychain password for service ${service}: ${error.message}`);
  }
}
