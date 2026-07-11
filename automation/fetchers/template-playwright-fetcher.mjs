import { downloadByRole, gotoAllowed } from "../src/fetcher-api.mjs";

export async function fetch({ page, source, downloadsDir }) {
  if (!page) {
    throw new Error("this fetcher requires browser access");
  }
  if (!source.startUrl) {
    throw new Error(`${source.name}: startUrl is required`);
  }

  await gotoAllowed(page, source.startUrl, source);

  // Customize these selectors for one institution. Keep them specific. If the
  // site changes and the expected export control is not found exactly once, the
  // helper throws and the scheduled job fails closed.
  const file = await downloadByRole(
    page,
    "link",
    /download transactions|export transactions/i,
    source,
    downloadsDir,
    `${source.name}.csv`,
  );

  return { files: [file] };
}
