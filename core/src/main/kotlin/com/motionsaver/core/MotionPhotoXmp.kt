package com.motionsaver.core

/**
 * 生成 Google / 小米「动态照片」(Motion Photo) 所需的 XMP 元数据。
 *
 * 两套字段同时写入以覆盖不同系统版本的相册实现：
 *  - 经典 MicroVideo：GCamera:MicroVideoVersion / MicroVideoOffset / MicroVideoPresentationTimestampUs
 *  - 新版 MotionPhoto：GCamera:MotionPhoto / MotionPhotoVersion + Container:Directory
 *
 * [videoLengthBytes] 是尾部追加的 MP4 字节数，规范定义为「从文件末尾到视频起始的偏移」。
 */
object MotionPhotoXmp {

    const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    fun build(videoLengthBytes: Long, presentationTimestampUs: Long = 0L): String {
        require(videoLengthBytes > 0) { "videoLengthBytes must be positive" }
        val len = videoLengthBytes.toString()
        val ts = presentationTimestampUs.toString()

        // 标准 XMP packet；padding 允许相册后续原地修改而不移动视频数据
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"MotionSaver 1.0\">\n")
            append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("  <rdf:Description rdf:about=\"\"\n")
            append("    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
            append("    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n")
            append("    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n")
            // 真机样本（小米 MVIMG_*.jpg）带 MicroVideo="1"；缺少它时部分小米相册
            // 会把文件当普通静态图，即使 MicroVideoOffset / Item:Length 都正确。
            append("    GCamera:MicroVideo=\"1\"\n")
            append("    GCamera:MicroVideoVersion=\"1\"\n")
            append("    GCamera:MicroVideoOffset=\"").append(len).append("\"\n")
            append("    GCamera:MicroVideoPresentationTimestampUs=\"").append(ts).append("\"\n")
            append("    GCamera:MotionPhoto=\"1\"\n")
            append("    GCamera:MotionPhotoVersion=\"1\"\n")
            append("    GCamera:MotionPhotoPresentationTimestampUs=\"").append(ts).append("\">\n")
            append("    <Container:Directory>\n")
            append("     <rdf:Seq>\n")
            append("      <rdf:li rdf:parseType=\"Resource\">\n")
            append("       <Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>\n")
            append("      </rdf:li>\n")
            append("      <rdf:li rdf:parseType=\"Resource\">\n")
            append("       <Container:Item Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"").append(len).append("\"/>\n")
            append("      </rdf:li>\n")
            append("     </rdf:Seq>\n")
            append("    </Container:Directory>\n")
            append("  </rdf:Description>\n")
            append(" </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            // 4KB padding，符合 XMP 规范对可写 packet whitespace 的建议
            append(" ".repeat(4096))
            append("\n<?xpacket end=\"w\"?>")
        }
    }
}
