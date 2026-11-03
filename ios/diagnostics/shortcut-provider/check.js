// Evaluate in cua_repl, then pass the selected Device Hub app binding.
async function checkAutomaticShortcut(app, title, successPattern) {
  const before = await app.getAXState({ disableDiffing: true, emit: false });
  const line = before.split('\n').find((value) => value.includes(`element Description: ${title},`));
  const match = line?.match(/^\s*(\d+) /);
  if (!match) return { verdict: 'INCONCLUSIVE', reason: 'Automatic tile is not on screen' };
  await app.click(Number(match[1]));
  let result = await app.getAXState({ emit: false });
  const verdict = (state) => {
    if (state.includes('Unable to run App Shortcut')) return { verdict: 'FAIL', reason: 'Unable to run App Shortcut' };
    if (successPattern.test(state)) return { verdict: 'PASS', reason: 'Expected intent result is visible' };
    return null;
  };
  const first = verdict(result);
  if (first) return first;
  // The first observation may capture the system's app-opening transition.
  result = (await app.getAXStateAndScreenshot({ disableDiffing: true, emit: false })).state;
  return verdict(result) ?? { verdict: 'INCONCLUSIVE', reason: 'App transition has not produced an observed result' };
}
