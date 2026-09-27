import { Upload } from "lucide-react"
import Image from "next/image"
export function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <div className="w-32 h-32 mb-6 opacity-50">
        <Image src="/icons/pwa-192.png" alt="" width={128} height={128} className="w-full h-full object-contain" />
      </div>
      <h3 className="text-2xl font-semibold text-foreground mb-2">暂无文件</h3>
      <p className="text-foreground/60 max-w-md mb-6">
        拖拽文件到这里，或点击上传按钮，开始使用你的资源库。
      </p>
      <div className="flex items-center gap-2 text-sm text-primary">
        <Upload className="h-4 w-4" />
        <span>支持图片、音频、视频和文档</span>
      </div>
    </div>
  )
}
