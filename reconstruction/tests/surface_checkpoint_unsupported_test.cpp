#include "surface_checkpoint_fixture.hpp"
#include <linux/fs.h>
#include <map>
#include <sys/syscall.h>

// Only this test translation unit substitutes the syscall expression. Shipping
// source has no hooks, and the real store/cleanup/validation code executes here.
namespace {
int unsupportedError=ENOSYS;
std::size_t publicationCalls=0;
long unavailableRename(long number,int oldDirectory,const char* oldName,
                       int newDirectory,const char* newName,unsigned flags) {
    require(number==SYS_renameat2&&oldDirectory==newDirectory&&
            std::string(oldName).starts_with(std::string(rf::surface_checkpoint::FILENAME)+".partial-")&&
            std::string(newName)==rf::surface_checkpoint::FILENAME&&flags==RENAME_NOREPLACE,
            "unexpected publication syscall or missing no-replace flag");
    ++publicationCalls;errno=unsupportedError;return -1;
}
}
#define syscall unavailableRename
#include "../src/surface_checkpoint.cpp"
#undef syscall

int main() {
    Temp temporary;const auto views=photographs();
    rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);
    rf::DenseSettings settings;settings.nearDepth=3.2;settings.farDepth=7.2;settings.depthPlanes=56;
    const auto dense=rf::reconstructDense(views,settings);
    const rf::SurfaceSettings surfaceSettings;
    const auto mesh=rf::fuseDepthSurfaces(views,dense,surfaceSettings);
    require(dense.writtenDepthMaps==3&&mesh.triangles.size()>100,"invalid synthetic fixture");
    const auto snapshot=[&] {
        std::map<std::string,std::vector<std::uint8_t>> files;
        for(const auto& entry:std::filesystem::directory_iterator(temporary.directory))
            files.emplace(entry.path().filename().string(),read(entry.path()));
        return files;
    };
    const auto before=snapshot();
    require(before.size()==15,"expected three reference maps and twelve depth tiles");
    for(const int error:{ENOSYS,EINVAL,EOPNOTSUPP}) {
        unsupportedError=error;const auto previousCalls=publicationCalls;
        rf::surface_checkpoint::StoreDiagnostic diagnostic;bool created=true;
        require(!rf::surface_checkpoint::store(views,dense,mesh,surfaceSettings,&created,&diagnostic)&&!created,
                "unsupported publication must remain an optional cache miss");
        require(publicationCalls==previousCalls+1,"publication syscall was not reached exactly once");
        require(diagnostic.stage==rf::surface_checkpoint::StoreStage::Rename&&
                diagnostic.errorNumber==error&&diagnostic.component==-1,
                "unsupported publication diagnostic lost");
        require(snapshot()==before,"failed publication changed depth bytes or leaked final/temporary file");
    }
    std::cout<<"PASS ENOSYS EINVAL EOPNOTSUPP preserve all depth bytes without publication or temporary leaks\n";
}
