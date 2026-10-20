#include "realityforge/vulkan_depth.hpp"
#include "cost_reduce_spv.hpp"
#include <vulkan/vulkan.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <optional>
#include <stdexcept>

namespace rf::vulkan_depth {
std::vector<Depth> cpuReference(const CostVolume& v,const Budget& budget){
    validate(v,budget);std::vector<Depth> output(v.pixels);
    const float invStep=(1/v.farDepth-1/v.nearDepth)/static_cast<float>(v.planes-1);
    for(std::size_t pixel=0;pixel<v.pixels;++pixel){
        if((pixel&63u)==0){cancellation::checkpoint();}
        if(!v.valid[pixel])continue;
        auto costs=v.costs.subspan(pixel*v.planes,v.planes);std::size_t best=0;
        for(std::size_t k=1;k<v.planes;++k)if(costs[k]<costs[best])best=k;
        const float cost=costs[best];if(best<2||best>=v.planes-2||cost>1-v.minimumNcc)continue;
        float runner=2;for(std::size_t k=0;k<v.planes;++k)if(std::abs(static_cast<int>(k)-static_cast<int>(best))>3)runner=std::min(runner,costs[k]);
        const float margin=runner-cost;if(margin<v.minimumUniqueness)continue;
        float inverse=1/v.nearDepth+static_cast<float>(best)*invStep;
        const float a=costs[best-1],c=costs[best+1],denom=a-2*cost+c;
        if(denom>1e-8f)inverse+=invStep*std::clamp(.5f*(a-c)/denom,-.75f,.75f);
        if(inverse<=0)continue;
        output[pixel]={1/inverse,std::clamp(margin/.20f,0.f,1.f)*std::clamp((1-cost)/.9f,0.f,1.f)};
    }return output;
}
namespace {
void checked(VkResult r,const char* operation){if(r!=VK_SUCCESS)throw Error(std::string("Vulkan ")+operation+" failed: "+std::to_string(r));}
struct Context {
    VkInstance instance{};VkPhysicalDevice physical{};VkDevice device{};VkQueue queue{};std::uint32_t family{};
    VkShaderModule shader{};VkDescriptorSetLayout descriptorLayout{};VkPipelineLayout layout{};VkPipeline pipeline{};
    VkDescriptorPool descriptors{};VkCommandPool commands{};VkFence fence{};
    struct Buffer{VkBuffer buffer{};VkDeviceMemory memory{};void* mapped{};VkDeviceSize bytes{};};
    std::array<Buffer,3> buffers{};
    ~Context(){
        if(device){vkDeviceWaitIdle(device);for(auto& b:buffers){if(b.mapped)vkUnmapMemory(device,b.memory);if(b.buffer)vkDestroyBuffer(device,b.buffer,nullptr);if(b.memory)vkFreeMemory(device,b.memory,nullptr);}if(fence)vkDestroyFence(device,fence,nullptr);if(commands)vkDestroyCommandPool(device,commands,nullptr);if(descriptors)vkDestroyDescriptorPool(device,descriptors,nullptr);if(pipeline)vkDestroyPipeline(device,pipeline,nullptr);if(layout)vkDestroyPipelineLayout(device,layout,nullptr);if(descriptorLayout)vkDestroyDescriptorSetLayout(device,descriptorLayout,nullptr);if(shader)vkDestroyShaderModule(device,shader,nullptr);vkDestroyDevice(device,nullptr);}if(instance)vkDestroyInstance(instance,nullptr);
    }
    Capability initialize(){
        VkApplicationInfo application{};application.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;application.pApplicationName="RealityForge experimental depth parity";application.apiVersion=VK_API_VERSION_1_1;
        VkInstanceCreateInfo create{};create.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;create.pApplicationInfo=&application;
        checked(vkCreateInstance(&create,nullptr,&instance),"instance");
        Capability out;out.loader=true;
        std::uint32_t count=0;checked(vkEnumeratePhysicalDevices(instance,&count,nullptr),"device enumeration");
        if(count==0)return out;
        std::vector<VkPhysicalDevice> devices(count);checked(vkEnumeratePhysicalDevices(instance,&count,devices.data()),"device enumeration");
        for(auto candidate:devices){std::uint32_t families=0;vkGetPhysicalDeviceQueueFamilyProperties(candidate,&families,nullptr);std::vector<VkQueueFamilyProperties> queues(families);vkGetPhysicalDeviceQueueFamilyProperties(candidate,&families,queues.data());for(std::uint32_t i=0;i<families;++i)if(queues[i].queueCount&&(queues[i].queueFlags&VK_QUEUE_COMPUTE_BIT)){physical=candidate;family=i;break;}if(physical)break;}
        if(!physical)return out;
        VkPhysicalDeviceProperties properties;vkGetPhysicalDeviceProperties(physical,&properties);out.compute=true;out.device=properties.deviceName;out.maxStorageBytes=properties.limits.maxStorageBufferRange;
        VkPhysicalDeviceMemoryProperties memory;vkGetPhysicalDeviceMemoryProperties(physical,&memory);for(std::uint32_t i=0;i<memory.memoryTypeCount;++i)if((memory.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))out.hostVisibleStorage=true;
        return out;
    }
    std::size_t buffer(std::size_t slot,VkDeviceSize bytes,std::size_t remaining){
        auto& b=buffers[slot];b.bytes=bytes;VkBufferCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;info.size=bytes;info.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;checked(vkCreateBuffer(device,&info,nullptr,&b.buffer),"buffer");
        VkMemoryRequirements requirements;vkGetBufferMemoryRequirements(device,b.buffer,&requirements);if(requirements.size>remaining)throw Error("Vulkan aligned allocation exceeds combined memory budget");
        VkPhysicalDeviceMemoryProperties properties;vkGetPhysicalDeviceMemoryProperties(physical,&properties);std::optional<std::uint32_t> type;
        for(std::uint32_t i=0;i<properties.memoryTypeCount;++i)if((requirements.memoryTypeBits&(1u<<i))&&((properties.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))){type=i;break;}
        if(!type)throw Error("Vulkan host coherent storage unsupported");
        VkMemoryAllocateInfo allocation{};allocation.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;allocation.allocationSize=requirements.size;allocation.memoryTypeIndex=*type;checked(vkAllocateMemory(device,&allocation,nullptr,&b.memory),"allocation");checked(vkBindBufferMemory(device,b.buffer,b.memory,0),"buffer binding");checked(vkMapMemory(device,b.memory,0,bytes,0,&b.mapped),"map");return static_cast<std::size_t>(requirements.size);
    }
};
std::vector<Depth> dispatch(Context& c,const CostVolume& v,const Budget& budget,std::size_t& allocated){
    cancellation::checkpoint();VkPhysicalDeviceProperties properties;vkGetPhysicalDeviceProperties(c.physical,&properties);
    const auto bytes=v.costs.size()*sizeof(float),maskBytes=v.valid.size()*sizeof(std::uint32_t),outputBytes=v.pixels*sizeof(Depth);
    if(bytes>properties.limits.maxStorageBufferRange||maskBytes>properties.limits.maxStorageBufferRange||outputBytes>properties.limits.maxStorageBufferRange||
       (v.pixels+63)/64>properties.limits.maxComputeWorkGroupCount[0]||properties.limits.maxComputeWorkGroupInvocations<64||properties.limits.maxComputeWorkGroupSize[0]<64)throw Error("Vulkan compute tile limits unsupported");
    const float priority=1;VkDeviceQueueCreateInfo queue{};queue.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;queue.queueFamilyIndex=c.family;queue.queueCount=1;queue.pQueuePriorities=&priority;VkDeviceCreateInfo device{};device.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;device.queueCreateInfoCount=1;device.pQueueCreateInfos=&queue;checked(vkCreateDevice(c.physical,&device,nullptr,&c.device),"logical device");vkGetDeviceQueue(c.device,c.family,0,&c.queue);
    allocated=bytes+maskBytes+2*outputBytes;for(std::size_t i=0;i<3;++i){const std::array<std::size_t,3> sizes{bytes,maskBytes,outputBytes};allocated+=c.buffer(i,sizes[i],budget.maxCombinedBytes-allocated);}
    std::memcpy(c.buffers[0].mapped,v.costs.data(),bytes);std::memcpy(c.buffers[1].mapped,v.valid.data(),maskBytes);
    VkShaderModuleCreateInfo shader{};shader.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;shader.codeSize=sizeof(detail::costReduceSpv);shader.pCode=detail::costReduceSpv;checked(vkCreateShaderModule(c.device,&shader,nullptr,&c.shader),"shader");
    std::array<VkDescriptorSetLayoutBinding,3> bindings{};for(std::uint32_t i=0;i<3;++i){bindings[i].binding=i;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;bindings[i].descriptorCount=1;bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;}
    VkDescriptorSetLayoutCreateInfo descriptorLayout{};descriptorLayout.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;descriptorLayout.bindingCount=3;descriptorLayout.pBindings=bindings.data();checked(vkCreateDescriptorSetLayout(c.device,&descriptorLayout,nullptr,&c.descriptorLayout),"descriptor layout");
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,24};VkPipelineLayoutCreateInfo layout{};layout.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;layout.setLayoutCount=1;layout.pSetLayouts=&c.descriptorLayout;layout.pushConstantRangeCount=1;layout.pPushConstantRanges=&push;checked(vkCreatePipelineLayout(c.device,&layout,nullptr,&c.layout),"pipeline layout");
    VkComputePipelineCreateInfo pipeline{};pipeline.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;pipeline.layout=c.layout;pipeline.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;pipeline.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;pipeline.stage.module=c.shader;pipeline.stage.pName="main";checked(vkCreateComputePipelines(c.device,VK_NULL_HANDLE,1,&pipeline,nullptr,&c.pipeline),"compute pipeline");
    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,3};VkDescriptorPoolCreateInfo pool{};pool.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;pool.maxSets=1;pool.poolSizeCount=1;pool.pPoolSizes=&poolSize;checked(vkCreateDescriptorPool(c.device,&pool,nullptr,&c.descriptors),"descriptor pool");
    VkDescriptorSetAllocateInfo allocate{};allocate.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;allocate.descriptorPool=c.descriptors;allocate.descriptorSetCount=1;allocate.pSetLayouts=&c.descriptorLayout;VkDescriptorSet set{};checked(vkAllocateDescriptorSets(c.device,&allocate,&set),"descriptor set");
    std::array<VkDescriptorBufferInfo,3> bufferInfos{};std::array<VkWriteDescriptorSet,3> writes{};for(std::uint32_t i=0;i<3;++i){bufferInfos[i]={c.buffers[i].buffer,0,c.buffers[i].bytes};writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[i].dstSet=set;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&bufferInfos[i];}vkUpdateDescriptorSets(c.device,3,writes.data(),0,nullptr);
    VkCommandPoolCreateInfo commandPool{};commandPool.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;commandPool.queueFamilyIndex=c.family;checked(vkCreateCommandPool(c.device,&commandPool,nullptr,&c.commands),"command pool");VkCommandBufferAllocateInfo commandAllocate{};commandAllocate.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;commandAllocate.commandPool=c.commands;commandAllocate.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;commandAllocate.commandBufferCount=1;VkCommandBuffer command{};checked(vkAllocateCommandBuffers(c.device,&commandAllocate,&command),"command buffer");
    VkCommandBufferBeginInfo begin{};begin.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;checked(vkBeginCommandBuffer(command,&begin),"begin command");vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,c.pipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,c.layout,0,1,&set,0,nullptr);
    struct Params{std::uint32_t pixels,planes;float nearDepth,farDepth,minimumNcc,minimumUniqueness;} params{static_cast<std::uint32_t>(v.pixels),static_cast<std::uint32_t>(v.planes),v.nearDepth,v.farDepth,v.minimumNcc,v.minimumUniqueness};static_assert(sizeof(Params)==24);
    vkCmdPushConstants(command,c.layout,VK_SHADER_STAGE_COMPUTE_BIT,0,24,&params);vkCmdDispatch(command,static_cast<std::uint32_t>((v.pixels+63)/64),1,1);
    VkMemoryBarrier barrier{};barrier.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER;barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,nullptr,0,nullptr);checked(vkEndCommandBuffer(command),"end command");
    VkFenceCreateInfo fence{};fence.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;checked(vkCreateFence(c.device,&fence,nullptr,&c.fence),"fence");VkSubmitInfo submit{};submit.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;submit.commandBufferCount=1;submit.pCommandBuffers=&command;cancellation::checkpoint();checked(vkQueueSubmit(c.queue,1,&submit,c.fence),"submit");
    // Submission memory cannot be freed while executing. Observe cancellation
    // after its bounded tile fence, before exposing or accepting any output.
    bool cancelled=false;
    for(;;){
        const auto ready=vkWaitForFences(c.device,1,&c.fence,VK_TRUE,1000000);
        if(ready==VK_SUCCESS)break;
        if(ready!=VK_TIMEOUT)checked(ready,"tile fence");
        try{cancellation::checkpoint();}catch(const cancellation::Cancelled&){cancelled=true;}
    }
    if(cancelled)throw cancellation::Cancelled();
    cancellation::checkpoint();
    std::vector<Depth> output(v.pixels);std::memcpy(output.data(),c.buffers[2].mapped,outputBytes);return output;
}
}
Capability capability(){try{Context c;return c.initialize();}catch(const std::exception&){return {};}}
Result reduce(const CostVolume& v,const Budget& budget,Selection selection){
    Result out;out.samples=cpuReference(v,budget);out.combinedBytes=v.costs.size()*sizeof(float)+v.valid.size()*sizeof(std::uint32_t)+v.pixels*sizeof(Depth);
    if(selection==Selection::CpuDefault){out.detail="CPU production default; Vulkan profile unqualified";return out;}
    try{Context c;auto cap=c.initialize();if(!cap.compute||!cap.hostVisibleStorage){out.fallback=Fallback::Unsupported;out.detail="No compatible Vulkan compute device/coherent storage";return out;}
        auto gpu=dispatch(c,v,budget,out.combinedBytes);
        for(std::size_t i=0;i<gpu.size();++i){if(!std::isfinite(gpu[i].cameraZ)||!std::isfinite(gpu[i].confidence)||std::abs(gpu[i].cameraZ-out.samples[i].cameraZ)>1e-4f*std::max(1.f,out.samples[i].cameraZ)||std::abs(gpu[i].confidence-out.samples[i].confidence)>1e-5f){out.fallback=Fallback::OutputMismatch;out.detail="Vulkan reduction failed CPU parity; CPU result retained";return out;}}
        out.samples=std::move(gpu);out.backend=Backend::Vulkan;out.fallback=Fallback::None;out.detail=cap.device+"; experimental validation only";
    }catch(const cancellation::Cancelled&){throw;}catch(const std::bad_alloc&){out.fallback=Fallback::Allocation;out.detail="Vulkan allocation failed; CPU retained";}catch(const std::exception& e){out.fallback=Fallback::DriverFailure;out.detail=e.what();}
    return out;
}
}
