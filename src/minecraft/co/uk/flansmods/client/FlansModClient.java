package co.uk.flansmods.client;

import java.io.File;
import java.util.HashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import co.uk.flansmods.api.IControllable;
import co.uk.flansmods.client.model.GunAnimations;
import co.uk.flansmods.common.FlansMod;
import co.uk.flansmods.common.InfoType;
import co.uk.flansmods.common.driveables.EntitySeat;
import co.uk.flansmods.common.guns.GunType;
import co.uk.flansmods.common.guns.IScope;
import co.uk.flansmods.common.guns.ItemGun;
import co.uk.flansmods.common.teams.Team;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.ObfuscationReflectionHelper;

public class FlansModClient extends FlansMod
{
	//Plane / Vehicle control handling
	/** Whether the player has received the vehicle tutorial text */
	public static boolean doneTutorial = false;
	/** Whether the player is in mouse control mode */
	public static boolean controlModeMouse = false;
	/** A delayer on the mouse control switch */
	public static int controlModeSwitchTimer = 20;
	
	/** The delay between shots / reloading */
	public static int shootTime;
	
	//Recoil variables
	/** The recoil applied to the player view by shooting */
	public static float playerRecoil;
	/** The amount of compensation to apply to the recoil in order to bring it back to normal */
	public static float antiRecoil;
	
	//Gun animations
	/** Gun animation variables for each entity holding a gun. Currently only applicable to the player */
	public static HashMap<EntityLivingBase, GunAnimations> gunAnimations = new HashMap<EntityLivingBase, GunAnimations>();
	
	//Scope variables
	/** A delayer on the scope button to avoid repeat presses */
	public static int scopeTime;
	/** The scope that is currently being looked down */
	public static IScope currentScope = null;
	/** The transition variable for zooming in / out with a smoother. 0 = unscoped, 1 = scoped */
	public static float zoomProgress = 0F, lastZoomProgress = 0F;
	/** The zoom level of the last scope used, for transitioning out of being scoped, even after the scope is forgotten */
	public static float lastZoomLevel = 1F, lastFOVZoomLevel = 1F;
    
	//Variables to hold the state of some settings so that after being hacked for scopes, they may be restored
	/** The player's mouse sensitivity setting, as it was before being hacked by my mod */
	public static float originalMouseSensitivity = 0.5F;
	/** The player's original FOV */
	public static float originalFOV = 90F;
	/** The original third person mode, before being hacked */
	public static int originalThirdPerson = 0;
	
	/** Whether the player is in a plane or not */
	public static boolean inPlane = false;
		
	public void load()
	{
		if (ABORT)
		{
			log("Failed to load dependencies! Not loading Flan's Mod.");
			return;
		}
		
		log("Loading Flan's mod.");

		MinecraftForge.EVENT_BUS.register(this);
	}
	
	
	
	@ForgeSubscribe
	public void renderLiving(RenderPlayerEvent.Pre event)
	{
		RendererLivingEntity.NAME_TAG_RANGE = 64F;
		RendererLivingEntity.NAME_TAG_RANGE_SNEAK = 32F;		
		if(event.entity instanceof EntityPlayer && GuiTeamScores.gametype != null && !"No Gametype".equals(GuiTeamScores.gametype))
		{
			GuiTeamScores.PlayerData rendering = GuiTeamScores.getPlayerData(event.entity.getEntityName());
			GuiTeamScores.PlayerData thePlayer = GuiTeamScores.getPlayerData(minecraft.thePlayer.username);
			
			Team renderingTeam = rendering == null ? Team.spectators : rendering.team.team;
			Team thePlayerTeam = thePlayer == null ? Team.spectators : thePlayer.team.team;
			
			//Spectators see all
			if(thePlayerTeam == Team.spectators)
				return;
			//Nobody sees spectators
			if(renderingTeam == Team.spectators)
			{
				event.setCanceled(true);
				return;
			}
			//If we are on the other team, don't render the name tag
			if(renderingTeam != thePlayerTeam)
			{
				RendererLivingEntity.NAME_TAG_RANGE = 0F;
				RendererLivingEntity.NAME_TAG_RANGE_SNEAK = 0F;
				return;
			}
			//If its DM, don't render the name tag
			if(!GuiTeamScores.sortedByTeam)
			{
				RendererLivingEntity.NAME_TAG_RANGE = 0F;
				RendererLivingEntity.NAME_TAG_RANGE_SNEAK = 0F;
				return;
			}
		}
	}

	public static void tick()
	{
		if (minecraft.thePlayer == null)
			return;
		
		if(minecraft.thePlayer.ridingEntity instanceof IControllable && minecraft.currentScreen == null)
			minecraft.displayGuiScreen(new GuiDriveableController((IControllable)minecraft.thePlayer.ridingEntity));
		
		// Guns
		if (shootTime > 0)
			shootTime--;
		if(scopeTime > 0)
			scopeTime--;
		if (playerRecoil > 0)
			playerRecoil *= 0.8F;
		minecraft.thePlayer.rotationPitch -= playerRecoil;
		antiRecoil += playerRecoil;

		minecraft.thePlayer.rotationPitch += antiRecoil * 0.2F;
		antiRecoil *= 0.8F;
		
		//Update gun animations for the gun in hand
		for(GunAnimations g : gunAnimations.values())
		{
			g.update();
		}		
		
		for(Object obj : minecraft.theWorld.playerEntities)
		{
			EntityPlayer player = (EntityPlayer)obj;
			ItemStack currentItem = player.getCurrentEquippedItem();
			if(currentItem != null && currentItem.getItem() instanceof ItemGun)
			{
				if(player == minecraft.thePlayer && minecraft.gameSettings.thirdPersonView == 0)
					player.clearItemInUse();
				else
				{
					player.setItemInUse(currentItem, 100);
				}
			}
		}

		//If the currently held item is not a gun or is the wrong gun, unscope
		Item itemInHand = null;
		ItemStack itemstackInHand = minecraft.thePlayer.inventory.getCurrentItem();
		if (itemstackInHand != null)
			itemInHand = itemstackInHand.getItem();
		if (currentScope != null && (itemInHand == null || !(itemInHand instanceof ItemGun && ((ItemGun)itemInHand).type.getCurrentScope(itemstackInHand) == currentScope)))
		{
			currentScope = null;
			minecraft.gameSettings.fovSetting = originalFOV;
			minecraft.gameSettings.mouseSensitivity = originalMouseSensitivity;
			minecraft.gameSettings.thirdPersonView = originalThirdPerson;
		}
		
		//Calculate new zoom variables
		lastZoomProgress = zoomProgress;
		if(currentScope == null)
		{
			zoomProgress *= 0.66F;
		}
		else
		{
			zoomProgress = 1F - (1F - zoomProgress) * 0.66F; 
		}
		
		if (minecraft.thePlayer.ridingEntity instanceof IControllable)
		{
			inPlane = true;
			try
			{
				ObfuscationReflectionHelper.setPrivateValue(EntityRenderer.class, minecraft.entityRenderer, ((IControllable)minecraft.thePlayer.ridingEntity).getPlayerRoll(), "camRoll", "N", "field_78495_O");
			} catch (Exception e)
			{
				log("I forgot to update obfuscated reflection D:");
				throw new RuntimeException(e);
			}			
			if(minecraft.thePlayer.ridingEntity instanceof IControllable)
			{
				try
				{
					ObfuscationReflectionHelper.setPrivateValue(EntityRenderer.class, minecraft.entityRenderer, ((IControllable)minecraft.thePlayer.ridingEntity).getCameraDistance(), "thirdPersonDistance", "A", "field_78490_B");
				} catch (Exception e)
				{
					log("I forgot to update obfuscated reflection D:");
					throw new RuntimeException(e);
				}		
			}
		}
		else if(inPlane)
		{
			try
			{
				ObfuscationReflectionHelper.setPrivateValue(EntityRenderer.class, minecraft.entityRenderer, 0F, "camRoll", "N", "field_78495_O");
			} catch (Exception e)
			{
				log("I forgot to update obfuscated reflection D:");
				throw new RuntimeException(e);
			}			
			try
			{
				ObfuscationReflectionHelper.setPrivateValue(EntityRenderer.class, minecraft.entityRenderer, 4.0F, "thirdPersonDistance", "A", "field_78490_B");
			} catch (Exception e)
			{
				log("I forgot to update obfuscated reflection D:");
				throw new RuntimeException(e);
			}	
			inPlane = false;
		}
		if (controlModeSwitchTimer > 0)
			controlModeSwitchTimer--;
		if (errorStringTimer > 0)
			errorStringTimer--;
	}
	
	public static void renderTick(float smoothing)
	{
		//If the zoom has changed sufficiently, update it via reflection
		if(Math.abs(zoomProgress - lastZoomProgress) > 0.0001F)
		{
			float actualZoomProgress = lastZoomProgress + (zoomProgress - lastZoomProgress) * smoothing;
			float botchedZoomProgress = zoomProgress > 0.8F ? 1F : 0F;
			double zoomLevel = botchedZoomProgress * lastZoomLevel + (1 - botchedZoomProgress);
			float FOVZoomLevel = actualZoomProgress * lastFOVZoomLevel + (1 - actualZoomProgress);
			if(Math.abs(zoomLevel - 1F) < 0.01F)
				zoomLevel = 1.0D;
			try
			{
				ObfuscationReflectionHelper.setPrivateValue(EntityRenderer.class, minecraft.entityRenderer, zoomLevel, "cameraZoom", "Y", "field_78503_V");
				minecraft.gameSettings.fovSetting = (((originalFOV * 40 + 70) / FOVZoomLevel) - 70) / 40;
			} 
			catch (Exception e)
			{
				log("I forgot to update obfuscated reflection D:");
				throw new RuntimeException(e);
			}
		}
	}
	
	@ForgeSubscribe
	public void chatMessage(ClientChatReceivedEvent event)
	{
		if(event.message.startsWith("{\"translate\":\"flanDeath."))
		{
			String[] split = event.message.split("\\.");
			split[split.length - 1] = split[split.length - 1].split("\"}")[0];
			event.setCanceled(true);
			TickHandlerClient.addKillMessage(split);
			//FMLClientHandler.instance().getClient().thePlayer.sendChatToPlayer(split[3] + " killed " + split[2] + " with a " + InfoType.getType(split[1]).name);
		}
	}
	
	@ForgeSubscribe
	public void entitySpawn(EntityJoinWorldEvent event)
	{
		/*
		if(event.entity.worldObj.isRemote && event.entity == Minecraft.getMinecraft().thePlayer)
			System.out.println(event.entity.toString());
		if(event.entity.worldObj.isRemote && event.entity instanceof EntityHorse)
			System.out.println(event.entity.toString());
		*/
	}
		
	private boolean checkFileExists(File file)
	{
		if(!file.exists())
		{
			try
			{ 
				file.createNewFile();
			}
			catch(Exception e)
			{
				FlansMod.log("Failed to create file");
				FlansMod.log(file.getAbsolutePath());
			}
			return false;
		}	
		return true;
	}

	public static boolean flipControlMode()
	{
		if (controlModeSwitchTimer > 0)
			return false;
		controlModeMouse = !controlModeMouse;
		FMLClientHandler.instance().getClient().displayGuiScreen(controlModeMouse ? new GuiDriveableController((IControllable)FMLClientHandler.instance().getClient().thePlayer.ridingEntity) : null);
		controlModeSwitchTimer = 40;
		return true;
	}
	
	public static void reloadModels()
	{
		for(InfoType type : InfoType.infoTypes)
		{
			type.reloadModel();
		}
	}
	
	public static Minecraft minecraft = FMLClientHandler.instance().getClient();
}
