#include "realityforge/surface.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <fstream>
#include <limits>
#include <cstdio>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
void expectError(auto&& callback){bool failed=false;try{callback();}catch(const rf::Error&){failed=true;}require(failed,"invalid geometry accepted");}
// A textured physically planar surface, observed by cameras at different positions.
// Images are actually rendered from world coordinates, not copied and shifted arbitrarily.
std::vector<rf::DenseView> fixture(int count=3,bool texture=true,bool occlude=false){
    constexpr int w=104,h=76;
    std::mt19937 engine(504);std::uniform_int_distribution<int> rgb(0,255);
    std::vector<std::array<int,3>> surface(256*256);
    for(auto& p:surface)p={rgb(engine),rgb(engine),rgb(engine)};
    auto sample=[&](double x,double y,int channel){
        x=std::clamp(x*31.+128.,0.,254.999);y=std::clamp(y*31.+128.,0.,254.999);
        int ix=static_cast<int>(x),iy=static_cast<int>(y);double a=x-ix,b=y-iy;
        auto pix=[&](int dx,int dy){return surface[(iy+dy)*256+ix+dx][channel];};
        return (1-a)*(1-b)*pix(0,0)+a*(1-b)*pix(1,0)+(1-a)*b*pix(0,1)+a*b*pix(1,1);
    };
    std::vector<rf::DenseView> views;
    for(int cam=0;cam<count;++cam){
        rf::DenseView view;view.width=w;view.height=h;
        view.fx=116;view.fy=116;view.cx=(w-1)/2.;view.cy=(h-1)/2.;
        view.pose={rf::identity(),{-cam*.55,0,0}};
        view.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            std::size_t idx=(y*w+x)*4;
            double wx=(x-view.cx)/view.fx*5.0+cam*.55;
            double wy=(y-view.cy)/view.fy*5.0;
            for(int c=0;c<3;++c)view.rgba[idx+c]=texture?static_cast<std::uint8_t>(std::round(sample(wx,wy,c))):128;
            view.rgba[idx+3]=occlude && cam==2 && x>20&&x<70&&y>15&&y<60?0:255;
        }
        views.push_back(std::move(view));
    }
    return views;
}
}
struct DiagonalFixture{std::vector<rf::DenseView> views;rf::DenseResult dense;};
DiagonalFixture diagonalFixture(double shift,bool rotate,bool reverse,bool partial,int shape,bool rectangular){
 DiagonalFixture f;f.views.resize(2);f.dense.maps.resize(2);
 for(int c=0;c<2;++c){int physical=reverse?1-c:c;auto&v=f.views[c];v.width=v.height=40;v.fx=100;v.fy=rectangular?110:100;v.cx=v.cy=19;v.pose={rf::identity(),{-physical*shift,0,0}};
 bool rot=physical&&rotate;if(rot)v.pose.rotation={0,-1,0,1,0,0,0,0,1};
 auto&m=f.dense.maps[c];m.width=m.height=40;m.depth.assign(1600,0);m.confidence.assign(1600,0);
 for(int y=3;y<37;y+=2)for(int x=3;x<37;x+=2){
 if(partial&&physical&&x<19)continue;
 double rx=(x-v.cx)/v.fx,ry=(y-v.cy)/v.fy; if(rot){double q=rx;rx=ry;ry=-q;}
 // Synthetic analytic surfaces only, directly test the dense-to-mesh API.
 double z=5;
 if(shape==1)z=10/(1+std::sqrt(1-.08*(rx*rx+ry*ry))); // paraboloid z=5+.004(x²+y²)
 if(shape==2)z=rx<0?5:6;
 if(shape==3)z=5/(1-.1*rx-.07*ry);
 double ox=physical*shift,oy=0;if(rot){ox=0;oy=-physical*shift;}
 rf::Vec3 p{rx*z+ox,ry*z+oy,z};m.depth[y*40+x]=z;m.confidence[y*40+x]=.9;
 f.dense.verifiedSamples.push_back({p,{128,128,128},.9f,2,(uint8_t)c,(uint16_t)x,(uint16_t)y});}}
 return f;
}

// Analytic observed cell: no depth/vertex exists at an omitted corner.
DiagonalFixture observedCell(unsigned mask,bool duplicate=false,double zLast=5.,double fy=100.){
    DiagonalFixture f;f.views.resize(2);f.dense.maps.resize(2);
    for(int camera=0;camera<2;++camera){
        auto& v=f.views[camera];v.width=v.height=40;v.fx=100;v.fy=fy;v.cx=v.cy=19;v.pose={rf::identity(),{0,0,0}};
        auto& map=f.dense.maps[camera];map.width=map.height=40;map.depth.assign(1600,0);map.confidence.assign(1600,0);
        for(int corner=0;corner<4;++corner){
            if(!(mask&(1u<<corner)))continue;
            const int x=15+(corner%2)*3,y=15+(corner/2)*3;const double z=corner==3?zLast:5.;
            map.depth[y*40+x]=z;map.confidence[y*40+x]=.9f;
            if(camera==0||duplicate)f.dense.verifiedSamples.push_back({{(x-v.cx)*z/v.fx,(y-v.cy)*z/v.fy,z},
                {128,128,128},.9f,2,static_cast<std::uint8_t>(camera),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
        }
    }
    return f;
}

int main(){
    int passed=0;
    auto test=[&](const char* name,auto fn){try{fn();++passed;std::cout<<"PASS "<<name<<'\n';}catch(const std::exception& e){std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n';throw;}};
    test("four observed corners choose the admissible diagonal before discarding faces",[]{
        for(double scale:{.01,1.,100.})for(int quarterTurns=0;quarterTurns<4;++quarterTurns)for(bool duplicate:{false,true}){
            auto f=observedCell(15,duplicate);
            // A measured cell near a depth discontinuity. Its shorter 0--3 diagonal
            // straddles rejected depth ranges in both faces; 0--1--2 is supported.
            const std::array<float,4> depths{4.99766506f,5.35574276f,4.95924027f,4.58438952f};
            for(auto& view:f.views)view.cx=0;
            for(auto& map:f.dense.maps){std::fill(map.depth.begin(),map.depth.end(),0);std::fill(map.confidence.begin(),map.confidence.end(),0);}
            for(std::size_t i=0;i<f.dense.verifiedSamples.size();++i){
                auto& sample=f.dense.verifiedSamples[i];const auto& view=f.views[sample.camera];
                const float z=static_cast<float>(depths[i%4]*scale);
                sample.xyz={(sample.x-view.cx)*z/view.fx,(sample.y-view.cy)*z/view.fy,z};
            }
            // Rotate the camera-local sampling grid, not the physical surface.
            // All four cases preserve exact source points and observed depths.
            for(int turn=0;turn<quarterTurns;++turn){
                const std::array<double,9> r{0,-1,0,1,0,0,0,0,1};
                for(auto& view:f.views){const double oldCx=view.cx;view.cx=33-view.cy;view.cy=oldCx;view.pose.rotation=rf::multiply(r,view.pose.rotation);}
                for(auto& sample:f.dense.verifiedSamples){const auto oldX=sample.x;sample.x=33-sample.y;sample.y=oldX;}
            }
            for(const auto& sample:f.dense.verifiedSamples)for(auto& map:f.dense.maps){
                map.depth[sample.y*map.width+sample.x]=static_cast<float>(sample.xyz.z);
                map.confidence[sample.y*map.width+sample.x]=sample.confidence;
            }
            const auto mesh=rf::fuseDepthSurfaces(f.views,f.dense);
            require(mesh.triangles.size()==1&&mesh.vertices.size()==3,"Short diagonal discarded an admissible observed triangle");
            const std::size_t sources=duplicate?2:1;
            require(mesh.acceptedSamples==4*sources&&mesh.observations.size()==3*sources&&mesh.unattachedObservations.size()==sources,"Adaptive diagonal lost source ledger");
            require(mesh.topology.nonmanifoldEdges==0&&mesh.topology.intersections==0&&!mesh.topology.intersectionBudgetExceeded,
                    "Adaptive diagonal introduced invalid topology");
            for(const auto& observation:mesh.observations){
                require(observation.sourceSampleId%4<3,"Adaptive diagonal bridged rejected fourth corner");
                const auto& input=f.dense.verifiedSamples[observation.sourceSampleId];
                const auto& output=mesh.vertices[observation.vertex];
                require(std::abs(input.xyz.x-output.xyz.x)<=scale*1e-12&&std::abs(input.xyz.y-output.xyz.y)<=scale*1e-12&&std::abs(input.xyz.z-output.xyz.z)<=scale*1e-12,
                        "Adaptive diagonal changed an observed position");
                require(observation.camera==input.camera&&observation.x==input.x&&observation.y==input.y,
                        "Adaptive diagonal changed source provenance");
            }
            const auto& t=mesh.triangles.front();const auto& a=mesh.vertices[t.a];const auto& b=mesh.vertices[t.b];const auto& c=mesh.vertices[t.c];
            require(t.sourceCamera==0,"Adaptive diagonal changed source camera");
            const double facing=a.normal.x*a.xyz.x+a.normal.y*a.xyz.y+a.normal.z*a.xyz.z;
            require(facing<0,"Adaptive diagonal reversed camera-facing winding");
            const double maxZ=std::max({a.xyz.z,b.xyz.z,c.xyz.z}),minZ=std::min({a.xyz.z,b.xyz.z,c.xyz.z});
            require((maxZ-minZ)/maxZ<=rf::SurfaceSettings{}.maximumRelativeDepthJump,"Adaptive diagonal relaxed the depth gate");
            for(const auto& observation:mesh.unattachedObservations)require(observation.sourceSampleId%4==3,"Rejected corner vanished from unattached evidence");
        }
    });
    test("exact quads share one diagonal across camera-local admission differences",[]{
        for(bool reverse:{false,true})for(int rotatedCamera:{-1,0,1}){
            auto f=observedCell(15,true);
            const std::array<float,4> depths{4.99766506f,5.35574276f,4.95924027f,4.99766506f*.92f};
            for(auto& view:f.views)view.cx=0;
            for(auto& map:f.dense.maps){std::fill(map.depth.begin(),map.depth.end(),0);std::fill(map.confidence.begin(),map.confidence.end(),0);}
            for(std::size_t i=0;i<f.dense.verifiedSamples.size();++i){
                auto& sample=f.dense.verifiedSamples[i];const auto& view=f.views[sample.camera];const auto z=depths[i%4];
                sample.xyz={(sample.x-view.cx)*z/view.fx,(sample.y-view.cy)*z/view.fy,z};
            }
            if(rotatedCamera>=0){
                auto& view=f.views[rotatedCamera];view.cx=14;view.cy=0;view.pose.rotation={0,-1,0,1,0,0,0,0,1};
                for(auto& sample:f.dense.verifiedSamples)if(sample.camera==rotatedCamera){const auto oldX=sample.x;sample.x=33-sample.y;sample.y=oldX;}
            }
            for(std::size_t i=0;i<f.dense.verifiedSamples.size();++i){
                const auto& sample=f.dense.verifiedSamples[i];auto& map=f.dense.maps[sample.camera];
                // Both maps satisfy the existing 0.001 source-depth agreement.
                // Their ±0.0005 uncertainty straddles the 0.08 face gate.
                const double sign=(sample.camera==0)^reverse?1.:-1.;
                const double perturb=i%4==0?sign:i%4==3?-sign:0.;
                map.depth[sample.y*map.width+sample.x]=static_cast<float>(sample.xyz.z*(1+perturb*.0005));
                map.confidence[sample.y*map.width+sample.x]=sample.confidence;
            }
            const auto mesh=rf::fuseDepthSurfaces(f.views,f.dense);
            require(mesh.triangles.size()==1,"Different camera gates introduced crossing exact-quad triangulations");
            require(mesh.topology.nonmanifoldEdges==0&&mesh.topology.intersections==0&&!mesh.topology.intersectionBudgetExceeded,
                    "Shared diagonal introduced a cross-view topology defect");
            require(mesh.acceptedSamples==8&&mesh.observations.size()+mesh.unattachedObservations.size()==8,"Shared diagonal lost source observations");
            require(mesh.triangles.front().sourceCamera==(reverse?0:1),"Shared diagonal accepted a face from the rejecting camera");
            for(const auto& o:mesh.observations)require(o.sourceSampleId%4!=1,"Shared diagonal emitted the conflicting alternative face");
        }
    });
    test("three observed corners retain exactly one supported face in every orientation",[]{
        for(int missing=0;missing<4;++missing){
            const auto f=observedCell(15u^(1u<<missing));
            const auto mesh=rf::fuseDepthSurfaces(f.views,f.dense);
            require(mesh.triangles.size()==1&&mesh.vertices.size()==3,"Three observed corners discarded or fabricated geometry");
            require(mesh.acceptedSamples==3&&mesh.observations.size()==3&&mesh.unattachedObservations.empty(),"Observed corner provenance lost");
            for(const auto& vertex:mesh.vertices)require(std::any_of(f.dense.verifiedSamples.begin(),f.dense.verifiedSamples.end(),[&](const auto& sample){
                return vertex.xyz.x==sample.xyz.x&&vertex.xyz.y==sample.xyz.y&&vertex.xyz.z==sample.xyz.z;
            }),"Partial cell introduced an unobserved vertex position");
            const auto& t=mesh.triangles.front();require(t.sourceCamera==0,"Face source camera changed");
            const auto a=mesh.vertices[t.a].xyz,b=mesh.vertices[t.b].xyz,c=mesh.vertices[t.c].xyz;
            const double nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
            require(nz<0&&std::abs(nz/2.+.01125)<1e-12,"Observed triangle area or camera-facing winding changed");
            for(const auto& o:mesh.observations){const auto& input=f.dense.verifiedSamples[o.sourceSampleId];
                require(o.camera==0&&o.x==input.x&&o.y==input.y,"Triangle invented source support");}
        }
    });
    test("partial cells retain depth edge area duplicate and insufficient-support gates",[]{
        for(unsigned mask=0;mask<16;++mask){
            if(__builtin_popcount(mask)>=3)continue;
            const auto f=observedCell(mask);require(rf::fuseDepthSurfaces(f.views,f.dense).triangles.empty(),"Insufficient observed corners fabricated a face");}
        const auto full=observedCell(15);require(rf::fuseDepthSurfaces(full.views,full.dense).triangles.size()==2,"Full cell behavior changed");
        const auto jump=observedCell(14,false,6.);require(rf::fuseDepthSurfaces(jump.views,jump.dense).triangles.empty(),"Partial cell bridged a depth discontinuity");
        const auto flat=observedCell(7);rf::SurfaceSettings tight;tight.maximumEdgePixelFactor=1.;
        require(rf::fuseDepthSurfaces(flat.views,flat.dense,tight).triangles.empty(),"Partial cell bypassed edge-length gate");
        const auto thin=observedCell(7,false,5.,100000.);require(rf::fuseDepthSurfaces(thin.views,thin.dense).triangles.empty(),"Partial cell bypassed area gate");
        auto invalid=flat;invalid.dense.maps[0].depth[15*40+15]=-1;
        expectError([&]{rf::fuseDepthSurfaces(invalid.views,invalid.dense);});
        const auto budget=diagonalFixture(0,false,false,false,0,false);rf::SurfaceSettings capped;capped.pixelStride=2;capped.maxTriangles=10;
        require(rf::fuseDepthSurfaces(budget.views,budget.dense,capped).triangles.size()<=10,"Triangle budget bypassed");
        const auto duplicate=observedCell(7,true);const auto mesh=rf::fuseDepthSurfaces(duplicate.views,duplicate.dense);
        require(mesh.triangles.size()==1,"Repeated observed face escaped duplicate gate");
        require(mesh.observations.size()==6&&mesh.unattachedObservations.empty(),"Duplicate face lost observed provenance");
    });
    test("exact equal diagonal ties share a world-space tessellation across rotated views",[]{
        for(int variant=0;variant<4;++variant){
            const bool reverse=variant==1,partial=variant==2,step=variant==3;
            const auto f=diagonalFixture(0,true,reverse,partial,step?2:0,false);
            rf::SurfaceSettings settings;settings.pixelStride=2;
            const auto mesh=rf::fuseDepthSurfaces(f.views,f.dense,settings);
            require(mesh.acceptedSamples==mesh.inputSamples,"Diagonal ownership changed accepted depth observations");
            require(mesh.triangles.size()==(step?480u:512u),"Coincident source quad acquired duplicate tessellations");
            require(mesh.topology.nonmanifoldEdges==0&&mesh.topology.intersections==0&&!mesh.topology.intersectionBudgetExceeded,
                    "Exact tied source quad caused overlapping or nonmanifold geometry");
            double area=0;
            for(const auto&t:mesh.triangles){
                const auto a=mesh.vertices[t.a].xyz,b=mesh.vertices[t.b].xyz,c=mesh.vertices[t.c].xyz;
                const rf::Vec3 u{b.x-a.x,b.y-a.y,b.z-a.z},v{c.x-a.x,c.y-a.y,c.z-a.z};
                area+=.5*std::hypot(u.y*v.z-u.z*v.y,u.z*v.x-u.x*v.z,u.x*v.y-u.y*v.x);
                require(a.z==b.z&&b.z==c.z,"Canonical tie bridged measured depth discontinuity");
            }
            require(std::abs(area-(step?2.9632:2.56))<1e-12,"Canonical tie lost analytic observed plane coverage");
            const std::size_t expectedSupport=step?94:partial?48:64;
            require(mesh.observations.size()==mesh.acceptedSamples&&mesh.unattachedObservations.empty(),"Exact duplicate cleanup lost accepted identities");
            require(std::count_if(mesh.observations.begin(),mesh.observations.end(),[](const auto&o){return o.relation==rf::SurfaceObservationRelation::ExactDuplicateFace;})==static_cast<std::ptrdiff_t>(expectedSupport),"Exact support relabeled averaging contributors");
            std::vector<bool> retained(f.dense.verifiedSamples.size());
            for(const auto&o:mesh.observations){
                require(o.sourceSampleId<retained.size()&&!retained[o.sourceSampleId],"Missing or duplicate source identity");
                retained[o.sourceSampleId]=true;const auto& source=f.dense.verifiedSamples[o.sourceSampleId];
                require(o.camera==source.camera&&o.x==source.x&&o.y==source.y,"Original source camera/pixel changed");
            }

        }
    });
    test("canonical diagonal does not conceal translated grid overlap",[]{
        const auto f=diagonalFixture(.01,false,false,false,0,false);
        rf::SurfaceSettings settings;settings.pixelStride=2;
        const auto mesh=rf::fuseDepthSurfaces(f.views,f.dense,settings);
        require(mesh.topology.nonmanifoldEdges>0&&mesh.topology.intersections>0,
                "Narrow exact-tie repair must not silently filter generic overlapping grids");
        require(mesh.acceptedSamples==578&&mesh.observations.size()==578,"Translated control discarded observations");
    });
    test("nonfinite observed sample is rejected before diagonal ordering",[]{
        auto f=diagonalFixture(0,true,false,false,0,false);
        f.dense.verifiedSamples.front().xyz.x=std::numeric_limits<double>::quiet_NaN();
        rf::SurfaceSettings settings;settings.pixelStride=2;
        expectError([&]{rf::fuseDepthSurfaces(f.views,f.dense,settings);});
    });
    test("confirmed depth samples produce genuine indexed triangles",[]{
        const auto images=fixture();
        rf::DenseSettings ds;ds.nearDepth=3.1;ds.farDepth=7;ds.depthPlanes=64;ds.pixelStride=2;
        const auto dense=rf::reconstructDense(images,ds);
        rf::SurfaceSettings ms;ms.pixelStride=2;
        auto mesh=rf::fuseDepthSurfaces(images,dense,ms);
        std::cout<<"confirmed="<<mesh.inputSamples<<" vertices="<<mesh.vertices.size()<<" faces="<<mesh.triangles.size()<<" fused="<<mesh.fusedObservations<<"\n";
        require(mesh.inputSamples>100,"No reciprocal depth samples");
        require(mesh.vertices.size()>80,"No fused vertices");
        require(mesh.triangles.size()>60,"Expected observed faces from textured plane");
        for(auto& v:mesh.vertices){
            require(std::abs(v.xyz.z-5.)<1.0,"Mesh contains unsupported distant depth");
            require(std::abs(std::hypot(v.normal.x,v.normal.y,v.normal.z)-1.)<.01,"Invalid mesh normal");
        }
        for(auto& t:mesh.triangles){
            require(t.a<mesh.vertices.size() && t.b<mesh.vertices.size() && t.c<mesh.vertices.size(),"Invalid index");
            require(t.a!=t.b&&t.b!=t.c&&t.c!=t.a,"Degenerate face");
        }
        require(rf::surfaceJson(mesh).find("\"triangleCount\":")!=std::string::npos,"Missing triangle data");
        rf::writeSurfaceGlb(mesh,"rf-test-surface.glb");
        rf::writeSurfacePly(mesh,"rf-test-surface.ply");
    });
    test("textureless inputs produce zero triangle faces",[]{
        const auto images=fixture(3,false);
        const auto dense=rf::reconstructDense(images);
        require(dense.verifiedSamples.empty(),"Uniform image yielded reciprocal points");
        auto mesh=rf::fuseDepthSurfaces(images,dense);
        require(mesh.triangles.empty(),"Unobserved geometry was filled");
        expectError([&]{rf::writeSurfaceGlb(mesh,"rf-invalid.glb");});
    });
    test("occluded input retains only confirmed geometry",[]{
        const auto images=fixture(3,true,true);
        rf::DenseSettings ds;ds.nearDepth=3.1;ds.farDepth=7.;ds.depthPlanes=64;ds.pixelStride=2;
        const auto dense=rf::reconstructDense(images,ds);
        rf::SurfaceSettings s;s.pixelStride=2;
        auto mesh=rf::fuseDepthSurfaces(images,dense,s);
        require(mesh.triangles.size()>20,"Occlusion erased all verified mesh");
    });
    test("depth-step barrier prevents triangles across measured discontinuity",[]{
        const auto views=fixture(2);
        rf::DenseResult dense;dense.maps.resize(views.size());
        for(auto& map:dense.maps){map.width=104;map.height=76;map.depth.assign(104*76,0);map.confidence.assign(104*76,0);}
        for(int y=3;y<73;y+=3)for(int x=3;x<101;x+=3){
            // An abrupt unobserved depth step is measured in both cameras.
            const float d=x<52?5.f:8.f;
            for(std::size_t cam=0;cam<views.size();++cam){
                const auto& v=views[cam];
                auto& m=dense.maps[cam];m.depth[y*104+x]=d;m.confidence[y*104+x]=.95f;
                const rf::Vec3 world{(x-v.cx)*d/v.fx+cam*.55,(y-v.cy)*d/v.fy,d};
                dense.verifiedSamples.push_back({world,{150,140,120},.95f,2,
                   static_cast<std::uint8_t>(cam),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
            }
        }
        rf::SurfaceSettings settings;settings.pixelStride=3;
        auto mesh=rf::fuseDepthSurfaces(views,dense,settings);
        require(mesh.triangles.size()>50,"Synthetic verified patches should form faces");
        for(auto t:mesh.triangles){
            const auto& a=mesh.vertices[t.a].xyz;
            const auto& b=mesh.vertices[t.b].xyz;
            const auto& c=mesh.vertices[t.c].xyz;
            const auto mn=std::min({a.z,b.z,c.z}),mx=std::max({a.z,b.z,c.z});
            require(mx-mn<1.,"Triangle bridged depth discontinuity");
        }
    });
    test("reject malformed source depth maps and unverified samples",[]{
        auto images=fixture(2);
        rf::DenseResult r;r.maps.resize(2);
        expectError([&]{rf::fuseDepthSurfaces(images,r);});
        for(auto& m:r.maps){m.width=104;m.height=76;m.depth.assign(104*76,0);m.confidence.assign(104*76,0);}
        r.verifiedSamples.push_back({{0,0,5},{1,2,3},.9f,1,0,3,3});
        expectError([&]{rf::fuseDepthSurfaces(images,r);});
        r.verifiedSamples[0].supportingViews=2;r.verifiedSamples[0].camera=100;
        expectError([&]{rf::fuseDepthSurfaces(images,r);});
    });
    test("surface serialization rejects malformed numeric data and indices",[]{
        rf::SurfaceMesh good;
        good.vertices={{{0,0,5},{0,0,-1},{1,2,3},.9f,2},
                       {{1,0,5},{0,0,-1},{1,2,3},.9f,2},
                       {{0,1,5},{0,0,-1},{1,2,3},.9f,2}};
        good.triangles={{0,1,2,0}};good.medianConfidence=.9f;
        require(rf::surfaceJson({}).find("\"triangleCount\":0")!=std::string::npos,
                "Empty diagnostic mesh must remain serializable");
        require(rf::surfaceJson(good).find("\"triangleCount\":1")!=std::string::npos,
                "Valid surface was rejected");
        const auto rejected=[&](const rf::SurfaceMesh& invalid){
            expectError([&]{rf::surfaceJson(invalid);});
            // Validation failure must not truncate an existing completed export.
            for(bool glb:{false,true}){
                const std::string path=glb?"rf-preserved-validation.glb":"rf-preserved-validation.ply";
                {std::ofstream output(path,std::ios::binary);output<<"original completed result";}
                expectError([&]{if(glb)rf::writeSurfaceGlb(invalid,path);else rf::writeSurfacePly(invalid,path);});
                std::ifstream input(path,std::ios::binary);
                const std::string content((std::istreambuf_iterator<char>(input)),{});
                require(content=="original completed result","Invalid mesh destroyed completed export");
                input.close();std::remove(path.c_str());
            }
        };
        auto bad=good;bad.medianConfidence=std::numeric_limits<float>::quiet_NaN();rejected(bad);
        bad=good;bad.vertices[0].confidence=std::numeric_limits<float>::infinity();rejected(bad);
        bad=good;bad.vertices[0].confidence=-.1f;rejected(bad);
        bad=good;bad.vertices[0].confidence=1.1f;rejected(bad);
        bad=good;bad.vertices[0].xyz.x=std::numeric_limits<double>::quiet_NaN();rejected(bad);
        bad=good;bad.vertices[0].normal.x=std::numeric_limits<double>::max();rejected(bad);
        bad=good;bad.triangles[0].c=3;rejected(bad);
        bad=good;bad.triangles[0].c=0;rejected(bad);
    });
    test("invalid C ABI produces JSON error",[]{
        char* result=rf_surface_json(nullptr,104,76,nullptr,2,3,7,32,3,nullptr,nullptr);
        require(result && std::string(result).find("error")!=std::string::npos,"Missing ABI error");rf_free(result);
    });
    test("deterministic fusion",[]{
        const auto images=fixture(2);
        rf::DenseSettings s;s.nearDepth=3.1;s.farDepth=7.0;s.depthPlanes=48;s.pixelStride=3;
        auto d=rf::reconstructDense(images,s);
        auto m1=rf::fuseDepthSurfaces(images,d),m2=rf::fuseDepthSurfaces(images,d);
        require(rf::surfaceJson(m1)==rf::surfaceJson(m2),"Fusion is nondeterministic");
    });
    std::cout<<passed<<" surface meshing cases passed\n";
}
