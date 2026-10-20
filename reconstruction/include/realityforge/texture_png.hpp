#pragma once
#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <cstdint>
#include <functional>
#include <span>
#include <vector>
#include <zlib.h>
namespace rf {
using PngSink=std::function<void(std::span<const std::uint8_t>)>;
namespace png_detail {
inline void big(std::vector<std::uint8_t>& v,std::uint32_t x){for(int i=3;i>=0;--i)v.push_back(static_cast<std::uint8_t>(x>>(i*8)));}
inline void chunk(const PngSink& sink,const char* name,std::span<const std::uint8_t> bytes){
    if(bytes.size()>65540)throw Error("PNG streaming chunk budget exceeded");
    std::vector<std::uint8_t> out(bytes.size()+12);
    const auto length=static_cast<std::uint32_t>(bytes.size());
    for(int i=0;i<4;++i){out[i]=static_cast<std::uint8_t>(length>>(24-8*i));out[4+i]=static_cast<std::uint8_t>(name[i]);}
    for(std::size_t i=0;i<bytes.size();++i)out[8+i]=bytes[i];
    std::uint32_t crc=0xffffffffu;
    for(std::size_t p=4;p<out.size()-4;++p){crc^=out[p];for(int k=0;k<8;++k)crc=(crc>>1)^(crc&1?0xedb88320u:0u);}
    crc=~crc;for(int i=0;i<4;++i)out[out.size()-4+i]=static_cast<std::uint8_t>(crc>>(24-8*i));
    sink(out);
}
}
// RFC1950/1951 stored blocks preserve the existing dependency-free lossless
// format. At most one <=65535-byte deflate block plus one PNG chunk is retained;
// there is no full-image filtered or zlib copy. Sink exceptions propagate.
inline void encodeRgbaPngStoredReference(int width,int height,std::span<const std::uint8_t> rgba,const PngSink& sink){
    if(width<1||height<1||width>4096||height>4096||rgba.size()!=static_cast<std::size_t>(width)*height*4||!sink)
        throw Error("Invalid bounded PNG input");
    cancellation::checkpoint();const std::array<std::uint8_t,8> magic{137,80,78,71,13,10,26,10};sink(magic);
    std::vector<std::uint8_t> header;png_detail::big(header,width);png_detail::big(header,height);header.insert(header.end(),{8,6,0,0,0});png_detail::chunk(sink,"IHDR",header);
    const std::array<std::uint8_t,2> zlib{0x78,0x01};png_detail::chunk(sink,"IDAT",zlib);
    const std::size_t rowBytes=static_cast<std::size_t>(width)*4,total=(rowBytes+1)*height;
    std::uint32_t a=1,b=0;
    for(std::size_t offset=0;offset<total;){
        cancellation::checkpoint();const auto n=std::min<std::size_t>(65535,total-offset);
        std::vector<std::uint8_t> block;block.reserve(n+5);block.push_back(offset+n==total?1:0);
        block.push_back(static_cast<std::uint8_t>(n));block.push_back(static_cast<std::uint8_t>(n>>8));
        const auto neg=static_cast<std::uint16_t>(~static_cast<std::uint16_t>(n));block.push_back(static_cast<std::uint8_t>(neg));block.push_back(static_cast<std::uint8_t>(neg>>8));
        for(std::size_t i=0;i<n;++i){const auto p=offset+i,y=p/(rowBytes+1),x=p%(rowBytes+1);const auto value=x==0?std::uint8_t{0}:rgba[y*rowBytes+x-1];block.push_back(value);a=(a+value)%65521u;b=(b+a)%65521u;}
        png_detail::chunk(sink,"IDAT",block);offset+=n;
    }
    std::vector<std::uint8_t> checksum;png_detail::big(checksum,(b<<16)|a);png_detail::chunk(sink,"IDAT",checksum);png_detail::chunk(sink,"IEND",{});
}
// Production encoder: platform/distro zlib, standard lossless PNG filters and
// a bounded row plus 32KiB output chunk. No full filtered-image/zlib copy.
inline void encodeRgbaPng(int width,int height,std::span<const std::uint8_t> rgba,const PngSink& sink){
    if(width<1||height<1||width>4096||height>4096||rgba.size()!=static_cast<std::size_t>(width)*height*4||!sink)
        throw Error("Invalid bounded PNG input");
    struct Deflater {
        z_stream stream{};
        Deflater(){if(deflateInit(&stream,Z_DEFAULT_COMPRESSION)!=Z_OK)throw Error("PNG zlib initialization failed");}
        ~Deflater(){deflateEnd(&stream);}
        Deflater(const Deflater&)=delete;Deflater& operator=(const Deflater&)=delete;
    } deflater;
    cancellation::checkpoint();const std::array<std::uint8_t,8> magic{137,80,78,71,13,10,26,10};sink(magic);
    std::vector<std::uint8_t> header;png_detail::big(header,width);png_detail::big(header,height);header.insert(header.end(),{8,6,0,0,0});png_detail::chunk(sink,"IHDR",header);
    std::array<std::uint8_t,32768> output{};
    auto pump=[&](int flush){
        int status=Z_OK;
        do {
            cancellation::checkpoint();deflater.stream.next_out=output.data();deflater.stream.avail_out=output.size();
            status=deflate(&deflater.stream,flush);
            if(status==Z_BUF_ERROR&&flush==Z_NO_FLUSH&&deflater.stream.avail_in==0)return;
            if(status!=Z_OK&&status!=Z_STREAM_END)throw Error("PNG compression failed");
            const auto produced=output.size()-deflater.stream.avail_out;
            if(produced)png_detail::chunk(sink,"IDAT",std::span<const std::uint8_t>(output).first(produced));
        }while(deflater.stream.avail_in||deflater.stream.avail_out==0||(flush==Z_FINISH&&status!=Z_STREAM_END));
    };
    const std::size_t rowBytes=static_cast<std::size_t>(width)*4;
    std::vector<std::uint8_t> row(rowBytes+1);row[0]=0;
    for(int y=0;y<height;++y){
        cancellation::checkpoint();std::copy_n(rgba.data()+static_cast<std::size_t>(y)*rowBytes,rowBytes,row.data()+1);
        deflater.stream.next_in=row.data();deflater.stream.avail_in=static_cast<uInt>(row.size());pump(Z_NO_FLUSH);
    }
    deflater.stream.next_in=nullptr;deflater.stream.avail_in=0;pump(Z_FINISH);png_detail::chunk(sink,"IEND",{});
}

}
