package com.realityforge.scanner.agents.a068;

import com.realityforge.scanner.viewer.PhotographicGlb;
import com.realityforge.scanner.viewer.PhotographicAsset;
import com.realityforge.scanner.viewer.loading.GlbStructure;
import com.realityforge.scanner.viewer.loading.GlbSummary;
import com.realityforge.scanner.viewer.loading.LoadedPhotographicModel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Standalone JVM corpus driver. Run against real android-json before mockable
 * android.jar; it executes the compiled production parser, no parser doubles.
 */
public final class GlbParserHostHarness {
    private static int checks;
    private static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("Parser corpus assertion " + checks); }
    private static void reject(byte[] bytes) {
        checks++;
        try { PhotographicGlb.INSTANCE.decode(bytes); throw new AssertionError("Accepted malformed corpus input " + checks); }
        catch (Exception expected) { }
    }
    private static byte[] chunk(String type, byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
        byte[] kind = type.getBytes("US-ASCII");out.write(kind);out.write(bytes);
        CRC32 crc=new CRC32();crc.update(kind);crc.update(bytes);
        out.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());return out.toByteArray();
    }
    private static byte[] png(boolean bomb, boolean truncated, int filter) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{-119,80,78,71,13,10,26,10});
        out.write(chunk("IHDR",ByteBuffer.allocate(13).putInt(2).putInt(2).put(new byte[]{8,6,0,0,0}).array()));
        byte[] pixels=new byte[bomb?100000:18];pixels[0]=(byte)filter;
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){z.write(pixels);}
        byte[] z=compressed.toByteArray();if(truncated)z=Arrays.copyOf(z,z.length-2);
        out.write(chunk("IDAT",z));out.write(chunk("IEND",new byte[0]));return out.toByteArray();
    }
    private static String json(int imageBytes) {
        return "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],"+
            "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"mode\":4,\"material\":0}]}],"+
            "\"buffers\":[{\"byteLength\":"+(108+imageBytes)+"}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"+
            "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":72,\"byteLength\":24},"+
            "{\"buffer\":0,\"byteOffset\":96,\"byteLength\":12},{\"buffer\":0,\"byteOffset\":108,\"byteLength\":"+imageBytes+"}],"+
            "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"+
            "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},{\"bufferView\":2,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"+
            "{\"bufferView\":3,\"componentType\":5125,\"count\":3,\"type\":\"SCALAR\"}],"+
            "\"materials\":[{\"doubleSided\":true,\"pbrMetallicRoughness\":{\"metallicFactor\":0,\"roughnessFactor\":1,\"baseColorTexture\":{\"index\":0}}}],"+
            "\"textures\":[{\"source\":0,\"sampler\":0}],\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,\"wrapS\":33071,\"wrapT\":33071}],"+
            "\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\"}]}";
    }
    private static byte[] binary(byte[] image) {
        ByteBuffer b=ByteBuffer.allocate(108+image.length).order(ByteOrder.LITTLE_ENDIAN);
        for(float f:new float[]{-2,-1,3,4,-1,3,-2,5,3})b.putFloat(f);
        for(int i=0;i<3;i++)b.putFloat(0).putFloat(0).putFloat(1);
        for(float f:new float[]{0,0,1,0,0,1})b.putFloat(f);
        b.putInt(0).putInt(1).putInt(2).put(image);return b.array();
    }
    private static byte[] pack(String text,byte[] binary) throws Exception {
        byte[] json=text.getBytes("UTF-8");int jl=(json.length+3)&~3,bl=(binary.length+3)&~3;
        ByteBuffer b=ByteBuffer.allocate(28+jl+bl).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(jl).putInt(0x4e4f534a).put(json);
        while(b.position()<20+jl)b.put((byte)32);
        b.putInt(bl).putInt(0x004e4942).put(binary);return b.array();
    }
    public static void main(String[] args) throws Exception {
        byte[] image=png(false,false,0),binary=binary(image);String text=json(image.length);
        byte[] valid=pack(text,binary),original=valid.clone();
        PhotographicAsset asset=PhotographicGlb.INSTANCE.decode(valid);
        check(asset.getPositions().length==9&&asset.getImageWidth()==2&&asset.getImageHeight()==2);
        check(Arrays.equals(asset.getIndices(),new int[]{0,1,2})&&Arrays.equals(valid,original));
        check(asset.getUv()[1]==0&&asset.getUv()[5]==1);
        for(String[] replacement:new String[][]{
            {"\"version\":\"2.0\"","\"version\":2.0"},
            {"\"nodes\":[0]","\"nodes\":[\"0\"]"},
            {"\"doubleSided\":true","\"doubleSided\":\"true\""},
            {"\"version\":\"2.0\"","\"version\":\"2.0\",\"version\":\"2.0\""},
            {"\"byteOffset\":36","\"byteOffset\":0"},
            {"\"byteOffset\":36","\"byteOffset\":2147483647"},
            {"\"count\":3","\"count\":0"},
            {"\"roughnessFactor\":1","\"roughnessFactor\":0.2"},
            {"\"doubleSided\":true","\"doubleSided\":true,\"normalTexture\":{\"index\":0}"},
            {"\"version\":\"2.0\"","\"version\":\"2.0\",\"extensions\":{\"EXT_unknown\":{}}"},
            {"\"bufferView\":4,\"mimeType\"","\"bufferView\":4,\"uri\":\"https://example.invalid/photo\",\"mimeType\""}
        }) reject(pack(text.replace(replacement[0],replacement[1]),binary));
        byte[] nan=binary.clone();ByteBuffer.wrap(nan).order(ByteOrder.LITTLE_ENDIAN).putFloat(0,Float.NaN);reject(pack(text,nan));
        for(byte[] badPng:new byte[][]{png(true,false,0),png(false,true,0),png(false,false,5)})reject(pack(json(badPng.length),binary(badPng)));
        ByteArrayOutputStream invalidChunk=new ByteArrayOutputStream();
        invalidChunk.write(image,0,33);invalidChunk.write(chunk("test",new byte[0]));invalidChunk.write(image,33,image.length-33);
        byte[] reserved=invalidChunk.toByteArray();reject(pack(json(reserved.length),binary(reserved)));
        Random random=new Random(68068);
        for(int i=0;i<100;i++){byte[] bad=valid.clone();int offset=random.nextInt(28);bad[offset]^=(byte)(1+random.nextInt(255));reject(bad);}
        boolean cancelled=false;try{PhotographicGlb.INSTANCE.decode(valid,()->true);}catch(java.util.concurrent.CancellationException expected){cancelled=true;}check(cancelled);
        File file=File.createTempFile("glb-corpus-",".glb");try{Files.write(file.toPath(),valid);check(PhotographicGlb.INSTANCE.read(file).getImageWidth()==2);}finally{file.delete();}
        File atlas=File.createTempFile("png-corpus-",".png");
        try {
            Files.write(atlas.toPath(),image);check(GlbStructure.INSTANCE.validatePng(atlas,()->false).getWidth()==2);
            Files.write(atlas.toPath(),png(true,false,0));
            boolean failed=false;try{GlbStructure.INSTANCE.validatePng(atlas,()->false);}catch(Exception expected){failed=true;}check(failed);
        }finally{atlas.delete();}
        for(int i=0;i<50;i++) {
            PhotographicAsset loaded=PhotographicGlb.INSTANCE.decode(valid);
            GlbSummary summary=GlbStructure.INSTANCE.validate(valid,4,()->false);
            LoadedPhotographicModel lease=new LoadedPhotographicModel(loaded,valid,summary);
            check(lease.glbBytes().isReadOnly()&&lease.getAsset().getImageWidth()==2);
            lease.close();lease.close();
            boolean closed=false;try{lease.getAsset();}catch(IllegalStateException expected){closed=true;}check(closed);
        }
        if(args.length>0)for(String name:new String[]{"sparse","dense","surface","texture"}) {
            File generated=new File(args[0],name+".glb");GlbSummary summary=GlbStructure.INSTANCE.validate(generated,null);
            check(summary.getVertexCount()==3&&summary.getPrimitiveMode()==(name.equals("sparse")||name.equals("dense")?0:4));
            if(name.equals("texture"))check(PhotographicGlb.INSTANCE.read(generated).getUv()[5]==1);
        }
        if(args.length>0)for(String name:new String[]{"legacy-sparse","legacy-surface","legacy-texture"}) {
            File generated=new File(args[0],name+".glb");
            if(!generated.isFile())continue;
            GlbSummary summary=GlbStructure.INSTANCE.validate(generated,null);
            check(summary.getVertexCount()==3&&summary.getPrimitiveMode()==(name.equals("legacy-sparse")?0:4));
            if(name.equals("legacy-texture"))check(PhotographicGlb.INSTANCE.read(generated).getImageWidth()==2);
        }
        System.out.println("Agent 068 production parser corpus: "+checks+" checks passed.");
    }
}
