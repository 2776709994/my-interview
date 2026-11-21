export async function readAnswerStream(
  response: Response,
  onChunk: (chunk: string) => void,
): Promise<void> {
  if (!response.ok || !response.headers.get('content-type')?.includes('text/event-stream')) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message || `请求失败 (${response.status})`);
  }

  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('无法获取响应流');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        throw new Error('回答流提前结束，请重试');
      }
      buffer += decoder.decode(value, { stream: true });

      let boundary = /\r?\n\r?\n/.exec(buffer);
      while (boundary) {
        const event = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const lines = event.split(/\r?\n/);
        const type = lines.find(line => line.startsWith('event:'))?.slice(6).trim();
        const data = lines.filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).replace(/^ /, ''))
          .join('\n');
        if (type === 'done') {
          return;
        }
        if (type === 'chunk' || type === 'error') {
          const body = JSON.parse(data);
          if (type === 'error') {
            throw new Error(body.message || '生成回答失败，请稍后重试');
          }
          if (typeof body.text !== 'string') {
            throw new Error('回答流格式无效');
          }
          onChunk(body.text);
        }
        boundary = /\r?\n\r?\n/.exec(buffer);
      }
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
