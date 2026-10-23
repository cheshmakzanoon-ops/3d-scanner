std::pair<std::vector<rf::DenseView>,rf::DenseResult> plane(bool opposite){
    std::vector<rf::DenseView> views(2);rf::DenseResult dense;dense.maps.resize(2);
    for(int camera=0;camera<2;++camera){
        auto& view=views[camera];view.width=view.height=32;view.fx=view.fy=100;view.cx=view.cy=15;view.pose.rotation=rf::identity();
        if(camera==1&&opposite){view.pose.rotation={-1,0,0,0,1,0,0,0,-1};view.pose.translation={0,0,10};}
        auto& map=dense.maps[camera];map.width=map.height=32;map.depth.assign(1024,0);map.confidence.assign(1024,0);
        for(int y=3;y<29;y+=3)for(int x=3;x<29;x+=3){
            const float z=camera==1&&opposite?4.99f:5.f;map.depth[y*32+x]=z;map.confidence[y*32+x]=.95f;
            const auto xyz=rf::multiply(rf::transpose(view.pose.rotation),rf::Vec3{(x-15)*z/100-view.pose.translation.x,(y-15)*z/100-view.pose.translation.y,z-view.pose.translation.z});
            dense.verifiedSamples.push_back({xyz,{150,140,130},.95f,2,static_cast<std::uint8_t>(camera),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
        }
    }return {views,dense};
}
