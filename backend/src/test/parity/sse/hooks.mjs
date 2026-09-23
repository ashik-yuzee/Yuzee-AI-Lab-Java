const stub = new URL('./genai-stub.mjs', import.meta.url).href;
export async function resolve(specifier, context, next) {
  if (specifier === '@google/genai') return { url: stub, shortCircuit: true };
  return next(specifier, context);
}
