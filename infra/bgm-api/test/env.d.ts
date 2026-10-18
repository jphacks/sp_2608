// Vite の ?raw インポート（ファイルの中身を文字列として読む）
declare module '*?raw' {
  const content: string;
  export default content;
}
