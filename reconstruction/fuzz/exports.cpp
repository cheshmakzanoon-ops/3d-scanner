#include "common.hpp"
#include "realityforge/core.hpp"
#include "realityforge/surface.hpp"
#include "realityforge/texture.hpp"
#include <fstream>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* bytes, std::size_t size) {
    static Temp temp;
    Input input{bytes,size};
    const auto mode=input.byte()%3;
    const auto path=(temp.dir/"output.glb").string();
    std::error_code error; std::filesystem::remove(path,error);
    if(mode==0) {
        rf::Result result; result.rotation=rf::identity(); result.translation={-1,0,0};
        const auto count=input.byte()%33;
        for(int i=0;i<count;++i) result.points.push_back({{input.number(),input.number(),input.number()},
            {input.byte(),input.byte(),input.byte()},0,1,static_cast<std::size_t>(i)});
        try {rf::writeGlb(result,path);} catch(const rf::Error&) {
            require(!std::filesystem::exists(path));
        }
    } else {
        rf::SurfaceMesh mesh;
        for(int i=0;i<3;++i) mesh.vertices.push_back({{double(i==1),double(i==2),3},{0,0,-1},{120,180,200},1,2});
        mesh.triangles.push_back({0,1,2,0});
        if(mode==1) {
            mesh.vertices[0].xyz={input.number(),input.number(),input.number()};
            mesh.triangles[0]={input.byte(),input.byte(),input.byte(),0};
            try {rf::writeSurfaceGlb(mesh,path);} catch(const rf::Error&) {
                require(!std::filesystem::exists(path));
            }
        } else {
            rf::TexturedSurface texture;texture.atlasWidth=texture.atlasHeight=1;texture.atlasRgba={128,128,128,255};
            for(int i=0;i<3;++i) texture.vertices.push_back({input.byte(),static_cast<float>(input.number()),static_cast<float>(input.number())});
            texture.indices={input.byte(),input.byte(),input.byte()};
            try {rf::writeTexturedGlb(mesh,texture,path);} catch(const rf::Error&) {
                require(!std::filesystem::exists(path));
            }
        }
    }
    if(std::filesystem::exists(path)) {
        require(std::filesystem::file_size(path)<1048576);
        std::ifstream stream(path,std::ios::binary);std::array<char,4> magic{};stream.read(magic.data(),4);
        require((magic==std::array<char,4>{'g','l','T','F'}));
    }
    return 0;
}
