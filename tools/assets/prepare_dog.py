"""Blender CLI: supplied skinned USDZ -> in-place Idle/Walk GLB.

Run with --background --factory-startup --python this_file -- --help.
Uses an explicit anatomical profile; rejects unskinned or incompatible inputs.
"""
import argparse, hashlib, json, math, pathlib, sys, zipfile
import bpy
from mathutils import Matrix, Vector, Quaternion


def parse():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',required=True,type=pathlib.Path)
    p.add_argument('--profile',required=True,type=pathlib.Path)
    p.add_argument('--output',required=True,type=pathlib.Path)
    p.add_argument('--work-dir',required=True,type=pathlib.Path)
    p.add_argument('--render',action='store_true')
    return p.parse_args(sys.argv[sys.argv.index('--')+1:])


def extract_usd(source, folder):
    if source.suffix.lower()!='.usdz': return source
    folder.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(source) as z:
        for item in z.infolist():
            target=(folder/item.filename).resolve()
            if not target.is_relative_to(folder.resolve()): raise ValueError('Unsafe USDZ member')
            z.extract(item,folder)
        roots=[folder/n for n in z.namelist() if pathlib.Path(n).suffix.lower() in ('.usd','.usda','.usdc')]
    if not roots: raise ValueError('USDZ contains no USD root layer')
    return roots[0]


def aim(rest,head,tail):
    direction=Vector(tail)-Vector(head)
    if direction.length<1e-6: raise ValueError('Degenerate target bone')
    q=(rest.to_3x3()@Vector((0,1,0))).rotation_difference(direction.normalized())
    m=q.to_matrix().to_4x4()@rest.copy()
    m.translation=Vector(head)
    return m


def two_bone(start,end,l1,l2,pole):
    delta=end-start
    distance=delta.length
    if distance>l1+l2-0.001: end=start+delta.normalized()*(l1+l2-0.001); delta=end-start; distance=delta.length
    if distance<abs(l1-l2)+0.001: end=start+delta.normalized()*(abs(l1-l2)+0.001); delta=end-start; distance=delta.length
    direction=delta.normalized()
    bend=Vector(pole)-direction*Vector(pole).dot(direction)
    bend.normalize()
    along=(l1*l1-l2*l2+distance*distance)/(2*distance)
    knee=start+direction*along+bend*math.sqrt(max(0,l1*l1-along*along))
    return knee,end


def evaluated_points(meshes):
    deps=bpy.context.evaluated_depsgraph_get()
    return [ob.matrix_world@v.co for ob in meshes for v in ob.evaluated_get(deps).data.vertices]


def set_pose(arm, rest, profile, phase, walking):
    bones=arm.pose.bones
    for b in bones: b.matrix_basis=Matrix.Identity(4)
    raise_z=profile['pelvis_raise']
    bob=(0.006*(1-math.cos(4*math.pi*phase))) if walking else .003*math.sin(2*math.pi*phase)
    shift=Vector((0,0,raise_z+bob))
    root=profile['root']
    bones[root].matrix=Matrix.Translation(shift)@rest[root]
    pivot=Vector(profile['torso_pivot'])
    torso=Matrix.Translation(shift+pivot)@Matrix.Rotation(math.radians(profile['torso_pitch_degrees']),4,'X')@Matrix.Translation(-pivot)
    for name in profile['torso_chain']:
        bones[name].matrix=torso@rest[name]
        bpy.context.view_layer.update()
    # Compensate neck so the ears stay above the shoulders after unfolding torso.
    neck=profile['neck']; target=torso@rest[neck]
    target=Matrix.Translation(target.translation)@Matrix.Rotation(math.radians(-25),4,'X')@Matrix.Translation(-target.translation)@target
    bones[neck].matrix=target
    bpy.context.view_layer.update()
    # Subtle head and tail motion; all axes are expressed in source armature space.
    head=bones[profile['head']]
    head.rotation_mode='QUATERNION'
    axis=rest[profile['head']].to_3x3().inverted()@Vector((0,0,1))
    head.rotation_quaternion=Quaternion(axis,.025*math.sin(2*math.pi*phase))
    for i,name in enumerate(profile['tail']):
        b=bones[name]; b.rotation_mode='QUATERNION'
        axis=rest[name].to_3x3().inverted()@Vector((0,0,1))
        b.rotation_quaternion=Quaternion(axis,.07*math.sin(2*math.pi*phase-i*.35))
    bpy.context.view_layer.update()
    for leg in profile['legs']:
        chain=leg['chain']; foot=Vector(leg['foot'])
        if walking:
            t=(phase+leg['phase'])%1; stance=profile['stance_fraction']; travel=profile['foot_travel_source_units']
            if t<stance:
                foot.y+=travel*(t/stance-.5)
            else:
                u=(t-stance)/(1-stance)
                smooth=u*u*(3-2*u)
                foot.y+=travel*(.5-smooth)
                foot.z+=profile['foot_lift_source_units']*math.sin(math.pi*u)**2
        if leg['role'].startswith('hind'):
            start=rest[chain[0]].translation+shift
            # Three anatomical links: thigh, shin, hock. Final paw keeps its rest orientation.
            hock=foot+Vector((0,.065,.135))
            knee,hock=two_bone(start,hock,arm.data.bones[chain[0]].length,arm.data.bones[chain[1]].length,leg['pole'])
            joints=[start,knee,hock,foot]
            for i in range(3):
                bones[chain[i]].matrix=aim(rest[chain[i]],joints[i],joints[i+1]); bpy.context.view_layer.update()
            m=rest[chain[3]].copy();m.translation=foot;bones[chain[3]].matrix=m
        else:
            start=(torso@rest[chain[0]]).translation
            shoulder=start+Vector((0,-.03,-arm.data.bones[chain[0]].length*.96))
            bones[chain[0]].matrix=aim(rest[chain[0]],start,shoulder);bpy.context.view_layer.update()
            wrist=foot+Vector((0,.027,.08))
            knee,wrist=two_bone(shoulder,wrist,arm.data.bones[chain[1]].length,arm.data.bones[chain[2]].length,leg['pole'])
            bones[chain[1]].matrix=aim(rest[chain[1]],shoulder,knee);bpy.context.view_layer.update()
            bones[chain[2]].matrix=aim(rest[chain[2]],knee,wrist);bpy.context.view_layer.update()
            bones[chain[3]].matrix=aim(rest[chain[3]],wrist,foot);bpy.context.view_layer.update()
            # Small toe chains retain the original bend and follow the translated paw.
            delta=foot-rest[chain[4]].translation
            for name in chain[4:]:
                m=rest[name].copy();m.translation+=delta;bones[name].matrix=m;bpy.context.view_layer.update()
    bpy.context.view_layer.update()


def render_preview(folder,meshes,arm,actions):
    scene=bpy.context.scene
    scene.render.engine='CYCLES';scene.cycles.samples=24
    scene.render.resolution_x=800;scene.render.resolution_y=800;scene.render.resolution_percentage=100
    if scene.world is None:scene.world=bpy.data.worlds.new('Preview world')
    scene.world.color=(.16,.16,.16)
    bpy.ops.object.camera_add(location=(1.15,-1.6,.95));cam=bpy.context.object
    cam.rotation_euler=(Vector((0,0,.23))-cam.location).to_track_quat('-Z','Y').to_euler();cam.data.type='ORTHO';cam.data.ortho_scale=.9;scene.camera=cam
    for loc,power,size in [((1,-1,2),130,2),((-1,-.4,.8),65,1.5),((0,1,1.5),90,1.5)]:
        bpy.ops.object.light_add(type='AREA',location=loc);light=bpy.context.object;light.data.energy=power;light.data.shape='DISK';light.data.size=size;light.rotation_euler=(-light.location).to_track_quat('-Z','Y').to_euler()
    bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.001));floor=bpy.context.object
    mat=bpy.data.materials.new('Preview floor');mat.diffuse_color=(.17,.19,.2,1);floor.data.materials.append(mat)
    for action in actions:
        arm.animation_data.action=action
        frames=[1] if action.name=='Idle' else [1,7,13,19,25,31]
        for frame in frames:
            scene.frame_set(frame);scene.render.filepath=str(folder/f'{action.name.lower()}-{frame:02d}.png');bpy.ops.render.render(write_still=True)
    # Side view is useful for detecting folded pelvis and planted paws.
    cam.location=(1.7,0,.5);cam.rotation_euler=(Vector((0,-.03,.23))-cam.location).to_track_quat('-Z','Y').to_euler()
    arm.animation_data.action=actions[1];scene.frame_set(1);scene.render.filepath=str(folder/'walk-side.png');bpy.ops.render.render(write_still=True)


def main():
    args=parse();source=args.source.resolve();out=args.output.resolve();work=args.work_dir.resolve();work.mkdir(parents=True,exist_ok=True);out.parent.mkdir(parents=True,exist_ok=True)
    profile=json.loads(args.profile.read_text(encoding='utf-8'))
    if profile['source_up']!='+Z' or profile['source_forward']!='-Y':raise ValueError('This solver requires an explicit +Z up, -Y forward profile; rotate USD/profile before solving')
    bpy.ops.wm.read_factory_settings(use_empty=True)
    layer=extract_usd(source,work/'unpacked');bpy.ops.wm.usd_import(filepath=str(layer))
    arm=bpy.data.objects.get(profile['armature'])
    if not arm or arm.type!='ARMATURE':raise ValueError('Compatible armature missing; source must already be skinned')
    meshes=[o for o in bpy.data.objects if o.type=='MESH' and any(m.type=='ARMATURE' and m.object==arm for m in o.modifiers)]
    if not meshes or not all(o.vertex_groups for o in meshes):raise ValueError('No skinned weighted mesh')
    required={profile['root'],profile['neck'],profile['head'],*profile['torso_chain'],*profile['tail'],*(n for l in profile['legs'] for n in l['chain'])}
    missing=required-set(arm.data.bones.keys())
    if missing:raise ValueError(f'Anatomical profile does not match input: {sorted(missing)}')
    if len(profile['legs'])!=4:raise ValueError('Four anatomical leg mappings required')
    images=[{'name':i.name,'size':list(i.size)} for i in bpy.data.images]
    if not images or any(min(i['size'])==0 for i in images):raise ValueError('Source textures missing or unreadable')
    for image in bpy.data.images:image.pack()
    rest={b.name:b.matrix_local.copy() for b in arm.data.bones}
    set_pose(arm,rest,profile,0,False)
    pts=evaluated_points(meshes);scale=profile['target_height_m']/(max(p.z for p in pts)-min(p.z for p in pts))
    # Object transform is retained for skin bind matrices; glTF exporter converts Z-up to Y-up.
    min_z=min(p.z for p in pts)
    for ob in [arm,*meshes]:
        ob.parent=None;ob.matrix_world=Matrix.Diagonal((scale,scale,scale,1));ob.location.z=-min_z*scale
    fps=profile['fps'];bpy.context.scene.render.fps=fps;arm.animation_data_create();actions=[]
    for name,seconds in [('Idle',2.4),('Walk',profile['walk_seconds'])]:
        action=bpy.data.actions.new(name);arm.animation_data.action=action;count=round(seconds*fps)
        for f in range(count+1):
            set_pose(arm,rest,profile,f/count,name=='Walk')
            for bone in arm.pose.bones:
                bone.rotation_mode='QUATERNION'
                for path in ['location','rotation_quaternion','scale']:bone.keyframe_insert(data_path=path,frame=f+1,group=bone.name)
        actions.append(action)
    for action in actions:
        track=arm.animation_data.nla_tracks.new();track.name=action.name
        strip=track.strips.new(action.name,1,action);strip.action_frame_start=1;strip.action_frame_end=action.frame_range[1]
        track.mute=True
    arm.animation_data.action=actions[0];bpy.context.scene.frame_set(1)
    # Choose only source mesh and rig; never export preview lights/floor/camera.
    bpy.ops.object.select_all(action='DESELECT')
    for ob in [arm,*meshes]:ob.select_set(True)
    bpy.context.view_layer.objects.active=arm
    bpy.ops.export_scene.gltf(filepath=str(out),export_format='GLB',use_selection=True,export_yup=True,export_animations=True,export_animation_mode='ACTIONS',export_force_sampling=True,export_frame_range=False,export_skins=True,export_all_influences=False,export_influence_nb=4,export_materials='EXPORT',export_image_format='AUTO',export_extras=True)
    report={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'source':source.name,'bones':len(arm.data.bones),'vertices':sum(len(m.data.vertices) for m in meshes),'textures':images,'profile':profile,'scale':scale,'rest_bounds_m':{'min':[min(p[i] for p in evaluated_points(meshes)) for i in range(3)],'max':[max(p[i] for p in evaluated_points(meshes)) for i in range(3)]},'walk_reference_speed_mps':profile['foot_travel_source_units']*scale/(profile['stance_fraction']*profile['walk_seconds']),'blender_version':bpy.app.version_string,'output_sha256':hashlib.sha256(out.read_bytes()).hexdigest()}
    (work/'conversion-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    bpy.ops.wm.save_as_mainfile(filepath=str(work/'animated.blend'))
    if args.render:render_preview(work,meshes,arm,actions)
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
