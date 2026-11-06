"""Integration readback of the exported GLB through Blender's actual glTF importer."""
import bpy,sys,json,pathlib,math,struct
from mathutils import Vector
args=sys.argv[sys.argv.index('--')+1:];path=pathlib.Path(args[0]);out=pathlib.Path(args[1]);out.mkdir(parents=True,exist_ok=True)
data=path.read_bytes();length,kind=struct.unpack_from('<II',data,12);gltf=json.loads(data[20:20+length])
assert {a['name'] for a in gltf.get('animations',[])}=={'Idle','Walk'},'Expected exact clip names'
assert len(gltf.get('skins',[]))==1,'Expected source skin'
assert len(gltf.get('images',[]))>=2 and all('bufferView' in x for x in gltf['images']),'Expected embedded textures'
assert all('JOINTS_0' in p['attributes'] and 'WEIGHTS_0' in p['attributes'] for m in gltf['meshes'] for p in m['primitives']),'Skin weights absent'
bpy.ops.wm.read_factory_settings(use_empty=True);bpy.ops.import_scene.gltf(filepath=str(path.resolve()))
arm=next(o for o in bpy.data.objects if o.type=='ARMATURE');meshes=[o for o in bpy.data.objects if o.type=='MESH' and any(m.type=='ARMATURE' and m.object==arm for m in o.modifiers)];scene=bpy.context.scene
def points():
    dg=bpy.context.evaluated_depsgraph_get()
    return [o.matrix_world@v.co for o in meshes for v in o.evaluated_get(dg).data.vertices]
report={'glb_bytes':len(data),'skin_joints':len(gltf['skins'][0]['joints']),'embedded_images':len(gltf['images']),'actions':[]}
for action in bpy.data.actions:
    for track in arm.animation_data.nla_tracks:track.mute=True
    arm.animation_data.action=action
    if action.slots:arm.animation_data.action_slot=action.slots[0]
    frame_start,frame_end=action.frame_range
    scene.frame_set(round(frame_start));base=points();max_delta=0;mins=[float('inf')]*3;maxs=[-float('inf')]*3
    for frame in range(round(frame_start),round(frame_end)+1,3):
        scene.frame_set(frame);p=points();max_delta=max(max_delta,max((a-b).length for a,b in zip(p,base)))
        for i in range(3):mins[i]=min(mins[i],min(v[i] for v in p));maxs[i]=max(maxs[i],max(v[i] for v in p))
    assert max_delta>.0005,f'Static clip {action.name}'
    report['actions'].append({'name':action.name,'frames':[frame_start,frame_end],'max_vertex_displacement_m':max_delta,'bounds_blender_z_up':{'min':mins,'max':maxs}})
(out/'glb-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
